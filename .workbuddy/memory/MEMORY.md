# 项目长期记忆（seckill-mall 学习商城秒杀系统）

## 项目定位
- `D:\CodeBase\seckill-mall`：高并发秒杀学习系统，Spring Boot 3 + Java 17 + Redis + MySQL + RabbitMQ。
- 用户把它内化为「面试可讲」项目，配套 `LEARNING_GUIDE.md`（新手完全学习手册，19 章，约 3700+ 行，含源码链路）。
- 用户习惯：自己先改项目小问题，再让我据此更新文档并对齐格式。

## 文档与代码同步约定
- `LEARNING_GUIDE.md` 必须随源码同步。已修复的坑统一标记【已修复】（原理仍要讲清，比单纯指出 Bug 更硬）；真正改进空间标【未修复】。
- 当前已修复点：Pub/Sub 引号 Bug（改 StringRedisTemplate）、Controller 统一异常、订单归属校验、`@RequireAdmin` 黑名单权限、发消息移出事务、缓存击穿改 `tryLock(50,0)` 看门狗续期（不再 sleep+递归）、注册/下单并发 DuplicateKeyException→REPEAT_ORDER、AuthInterceptor 过期踢下线（不再自动续期）、Caffeine 职责拆分（仅缓存商品详情，库存直读 Redis）、取消订单 `version+1` 同步、**取消订单逻辑删除（UPDATE status=2 状态机，订单行保留可审计，不再物理删除丢审计）**、订单支付状态机（amount 快照 + `uk_transaction_id` 支付幂等）、秒杀幂等加 DB 兜底 `selectValidOrder`（status IN (0,1)，替代并不存在的 `uk_user_product` 唯一索引）、用户三级缓存（Caffeine+Redis + `UserDTO` 脱敏 + 双 Pub/Sub 频道）、延时消息/死信消费补偿队列（DelayRetryService/DeadLetterRetryService）。

## 已知残留缺口（面试可主动提的改进方向）
- 补偿队列重试 3 次仍失败仅 log 丢弃，无「人工处理表」兜底（最终一致而非 100% 自愈）。
- 答题异步模块：无定时落库、非真批量、LPOP 出队即丢、while(true) 无上限。
- 本地 Guava 布隆过滤器不跨实例共享、不自动刷新；Key 命名不统一；死信队列单消费者（已有本地重试+补偿队列缓解，未根治）。

## 硬性约束（来自 AGENTS.md）
- 项目各阶段开发完成、整体交付后，**禁止执行任何代码推送远程仓库操作**（git push / 上传 GitHub·Gitee 等）。本次仅改本地文档，未触碰远程。
- 禁止删减 Redis 核心技术、MySQL 乐观锁/唯一索引、RabbitMQ 死信队列、自定义 bizExecutor 线程池等（详见 AGENTS.md 禁止行为清单）。
