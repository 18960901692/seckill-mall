package com.sygzcd.seckillmall.service.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.mapper.OrdersMapper;
import com.sygzcd.seckillmall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单全局对账（库存泄漏终极安全网）
 *
 * 背景：超时未支付订单的取消信号，原本完全依赖 RabbitMQ 延时消息链路
 * （下单发延时消息 → 30min 后死信投递 → OrderCancelConsumer 调 cancelOrder）。
 * 但链路任一处失败都可能让"取消信号"彻底丢失：
 *   - DelayRetryService 补偿队列（seckill:delay:retry）重发失败 → 原实现直接丢弃；
 *   - DeadLetterRetryService 补偿队列（seckill:dead:retry）重投失败 → 原实现"永久丢弃"。
 * 被丢弃的订单号在 Redis 中无任何痕迹，订单永远停在 status=0，预扣库存永久不释放 → 库存泄漏。
 *
 * 本任务直接从 DB 这一"真相源"对账：扫描所有「status=0 且 create_time 超过取消窗口」的订单，
 * 逐笔兜底 cancelOrder（该方法事务内 WHERE status=0 条件更新 + 幂等，已支付则 no-op）。
 * 无论上面哪条 MQ/Redis 补偿链路把订单号弄丢了，只要订单仍是超时未支付，这里都会兜底取消，
 * 因此它是覆盖所有"取消信号丢失"路径的终极安全网，与取消窗口（30min）严格对齐。
 */
@Slf4j
@Component
public class OrderReconcileService {

    /** 取消窗口：订单超过该时长仍未支付即视为超时。必须与 RabbitMQ 延时消息 TTL 一致（30min）。 */
    private static final int CANCEL_WINDOW_MINUTES = 30;

    /** 单次最多处理条数，避免单次扫描/回滚量过大。未处理完的订单下一轮（60s 后）继续。 */
    private static final int MAX_BATCH = 100;

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private OrderService orderService;

    /**
     * 每 60 秒扫描一次超时未支付订单，兜底取消释放库存。
     * fixedDelay（而非 fixedRate）保证上一轮跑完再等 60s，避免任务堆积。
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcileAgedUnpaidOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(CANCEL_WINDOW_MINUTES);

        List<Orders> aged = ordersMapper.selectList(
                new LambdaQueryWrapper<Orders>()
                        .eq(Orders::getStatus, 0)
                        .lt(Orders::getCreateTime, threshold)
                        .last("LIMIT " + MAX_BATCH)
        );
        if (aged.isEmpty()) {
            return;
        }

        int released = 0;
        for (Orders order : aged) {
            try {
                // cancelOrder 幂等：已支付/已取消则 no-op；未支付则释放库存 + 失效缓存 + 删防重 key
                orderService.cancelOrder(order.getOrderNo());
                released++;
            } catch (Exception e) {
                // 单笔失败不影响其余订单；cancelOrder 幂等，下一轮扫描会重试
                log.error("对账兜底取消失败，订单号: {}，将在下一轮重试", order.getOrderNo(), e);
            }
        }
        log.info("订单对账完成：扫描 {} 条超时未支付订单，成功兜底取消 {} 条", aged.size(), released);
    }
}
