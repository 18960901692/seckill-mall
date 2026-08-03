package com.sygzcd.seckillmall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.OrdersMapper;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.OrderService;
import com.sygzcd.seckillmall.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单服务实现
 * 职责：订单查询、取消订单（释放库存）
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ProductService productService;

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String USER_SECKILL_KEY = "seckill:user:";

    @Override
    public Orders getByOrderNo(String orderNo) {
        return ordersMapper.selectOne(
                new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, orderNo)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(String orderNo) {
        Orders order = getByOrderNo(orderNo);
        if (order == null || order.getStatus() != 0) {
            return; // 订单不存在或已处理
        }

        // 物理删除订单（配合唯一索引 uk_user_product，允许用户取消后重新抢购）
        // 并发场景：MQ死信消息与手动取消同时触发时，先执行的事务删除成功并回滚库存，
        // 后执行的事务 deleteById 返回 0，必须直接返回，避免库存被回滚两次
        int deleted = ordersMapper.deleteById(order.getId());
        if (deleted == 0) {
            log.info("订单已被其他请求取消，跳过库存回滚，订单号: {}", orderNo);
            return;
        }

        // 回滚 MySQL 库存（原子操作）
        productMapper.update(null, 
                new UpdateWrapper<Product>()
                        .eq("id", order.getProductId())
                        .setSql("stock = stock + 1")
        );

        // 回滚 Redis 库存
        String stockKey = STOCK_KEY + order.getProductId();
        redisTemplate.opsForValue().increment(stockKey);

        // 失效商品缓存（Caffeine + Redis + 广播通知其他实例）
        productService.invalidateCache(order.getProductId());

        // 删除用户抢购记录，允许重新抢购
        String userKey = USER_SECKILL_KEY + order.getProductId() + ":" + order.getUserId();
        redisTemplate.delete(userKey);

        log.info("订单取消成功（物理删除），订单号: {}, 商品ID: {}, 用户ID: {}", orderNo, order.getProductId(), order.getUserId());
    }

    @Override
    public List<Orders> getUserOrders(Long userId, int page, int size) {
        Page<Orders> pageObj = new Page<>(page, size);
        Page<Orders> result = ordersMapper.selectPage(
                pageObj,
                new LambdaQueryWrapper<Orders>()
                        .eq(Orders::getUserId, userId)
                        .orderByDesc(Orders::getCreateTime)
        );
        return result.getRecords();
    }
}
