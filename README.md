# 基于 Redis 的高并发学习商城秒杀系统

> 以 Redis 为核心实现缓存三大问题治理、Redisson 分布式锁防超卖、AOP+Lua 滑动窗口限流、ZSet 积分排行榜；搭配 MySQL 乐观锁做库存最终兜底，通过事务保证下单幂等性；使用 RabbitMQ 延时队列 + 三层补偿安全网实现超时订单自动回滚，库存零泄漏。

## 技术架构

```
                                    ┌─ 用户请求 ─┐
                                    └─────┬──────┘
                                          │
                          ┌───────────────┼───────────────┐
                          ▼               ▼               ▼
                    ┌──────────┐   ┌───────────┐   ┌───────────┐
                    │  Nginx   │   │ RateLimit │   │ BlackList │
                    │ 多实例   │   │ Semaphore │   │ 拦截器    │
                    └────┬─────┘   │+ ZSet滑动│   │ 违规5次   │
                         │         │ 窗口限流  │   │ 自动拉黑  │
                         │         └───────────┘   └───────────┘
                         ▼
                    ┌──────────────────────────────────────────────┐
                    │              SeckillService                  │
                    │                                              │
                    │  ① 布隆过滤器(防穿透，定时刷新)                │
                    │  ② 三级缓存查库存: Caffeine→Redis→MySQL      │
                    │  ③ Redisson 分布式锁(防超卖第一层)             │
                    │  ④ Redis DECR 预扣库存                       │
                    │  ⑤ MySQL 乐观锁 version(防超卖兜底)           │
                    │  ⑥ 编程式事务 + 防重双检查(Redis+DB)         │
                    │  ⑦ 发送延时消息(RabbitMQ 30min)              │
                    └───────────────────────┬──────────────────────┘
                                          │
              ┌───────────────────────────┼───────────────────────────┐
              ▼                           ▼                           ▼
        ┌───────────┐           ┌──────────────────┐       ┌──────────────────┐
        │  MySQL    │           │   Redis 缓存层    │       │  RabbitMQ 消息   │
        │          │           │                  │       │                  │
        │ product  │           │ product:{id}     │       │ 延时队列 30min   │
        │ orders   │           │  → ProductDTO    │       │     ↓            │
        │ user     │           │ seckill:stock:{} │       │ OrderCancelCons  │
        │          │           │  → 实时计数器     │       │  ─── 第1层 ──── │
        │          │           │ user:{id}        │       └────────┬─────────┘
        │          │           │  → UserDTO       │               │
        │          │           │ rank:score ZSet  │               │ 失败
        │          │           │ answer:queue     │               ▼
        │          │           │ blacklist:ip/set │       ┌──────────────────┐
        │          │           │ ratelimit:ip:{}  │       │ 第2层: 补偿重试  │
        └──────────┘           └──────────────────┘       │ DelayRetryService│
                                                          │ 重试队列→死信队列 │
                                                          └────────┬─────────┘
                                                                   │ 兜底
                                                                   ▼
                                                            ┌──────────────────┐
                                                            │ 第3层: 全局对账  │
                                                            │ OrderReconcile   │
                                                            │ Service          │
                                                            │ 每60s扫DB超时    │
                                                            │ 订单取消兜底      │
                                                            └──────────────────┘
```

## 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 基础框架 | Spring Boot | 3.3.2 |
| ORM | MyBatis-Plus | 3.5.7 |
| 缓存 | Redis + Caffeine | - |
| 分布式锁 | Redisson | 3.32.0 |
| 消息队列 | RabbitMQ | - |
| 布隆过滤器 | Guava | 33.2.0-jre |
| 接口文档 | Knife4j | 4.4.0 |
| 数据库 | MySQL | 8.0+ |


## 核心功能

### Redis 缓存治理
- **三级缓存**：Caffeine（本地）→ Redis（分布式）→ MySQL（DB），从顶向下逐层回源
- **缓存三大问题治理**：
  - 穿透：Guava 布隆过滤器预热全量商品ID，定时刷新兜底新增商品
  - 击穿：热点商品永不过期 + Redisson 互斥锁双重检查防击穿
  - 雪崩：TTL 随机抖动（30min ± 5min）
- **多实例缓存一致性**：Redis Pub/Sub 广播失效通知，所有实例收到后清除对应 Caffeine 缓存

### 用户缓存治理
- **用户三级缓存**：Caffeine → Redis → MySQL，与商品缓存架构一致
- **数据脱敏**：缓存使用 UserDTO（仅含 id、username、createTime），不含 password
- **分布式锁防击穿**：Redisson 锁 + 双重检查，确保高并发下用户查询不压垮 DB
- **多实例一致性**：独立 Pub/Sub 频道 `cache:invalidate:user` 广播用户缓存失效

### 并发控制
- **双层防超卖**：Redisson 分布式锁（第一层）+ MySQL 乐观锁 version（兜底）
- **双重限流**：Semaphore 本地限流（单机 10000 并发，仅做快速降级）+ Redis ZSet 滑动窗口分布式限流（秒杀接口 10000 次/秒）
- **用户防重**：Redis SetIfAbsent 快速过滤 + DB 状态查询兜底，双重保障防止重复下单

### 订单取消三层安全网
- **第 1 层（主路径）**：RabbitMQ 死信延时队列，下单 30 分钟后自动取消
- **第 2 层（补偿重试）**：Redis 重试队列（`seckill:delay:retry`）定时重试发送失败的延时消息，5 次重试上限后转入死信队列
- **第 3 层（全局对账）**：每 60 秒扫描 DB 中超时未支付订单，兜底取消——无论 MQ/Redis 链路是否正常工作

### 库存对账（最终兜底）
- **定时对账**：每 60 秒从 MySQL 查询所有商品库存，与 Redis 对比
- **分布式锁保护**：获取与秒杀同一把锁（`seckill:lock:{productId}`），加锁后双检确认
- **以 DB 为准**：Redis 库存与 MySQL 不一致时，以 MySQL 为准校正 Redis 库存
- **三层兜底体系**：代码补偿 → MQ 重试 → 库存对账，解决 JVM 宕机等极端异常

### 消息可靠性
- **RabbitMQ 延时队列**：死信队列 + TTL 实现 30 分钟未支付自动取消
- **消息可靠性三板斧**：Confirm 确认 + 持久化 + 手动 ACK
- **消费幂等**：`WHERE status=0` 条件更新，已支付/已取消的订单直接跳过

### 黑名单与安全
- **自动限流拉黑**：10 秒内违规 5 次自动加入黑名单（Redis Set），后续请求直接返回 403
- **黑白名单管理 API**：支持查看和手动移除黑名单 IP/用户
- **BCrypt 密码加密**
- **登录拦截器**：未登录请求返回 401
- **数据脱敏**：所有接口返回 UserDTO，不暴露 password 字段

### 布隆过滤器
- **启动预热**：CommandLineRunner 全量加载商品 ID
- **定时刷新**：每 5 分钟从 DB 全量重建，volatile 原子替换实例
- **即时补位**：新增商品时调用 `put()` 即时写入，无需等待定时刷新
- **防御性编程**：过滤器未就绪时放行所有请求，避免初始化窗口误拦截

### 答题与排行榜
- **异步批量落库**：Redis List 收集答题记录，每 5 秒定时批量写入 DB
- **幂等写入**：`ON DUPLICATE KEY UPDATE` 保证同一用户同一题目不重复记录
- **消息可靠性**：RPOPLPUSH 原子转移至处理队列，落库成功后再删除
- **积分排行榜**：Redis ZSet 存储用户积分，支持 Top N 查询和个人排名

## 快速开始

### 1. 环境准备
- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- RabbitMQ 3.10+（需创建 admin/admin123 用户）

### 2. 初始化数据库
```bash
mysql -u root -p < sql/schema.sql
```

### 3. 修改配置
编辑 `src/main/resources/application.yaml`，修改数据库密码、Redis 地址等。

### 4. 启动项目
```bash
mvn spring-boot:run
```

### 5. 访问接口文档
浏览器打开 http://localhost:8080/doc.html

## 接口列表

| 模块 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 用户 | /api/user/register | POST | 用户注册 |
| 用户 | /api/user/login | POST | 用户登录 |
| 用户 | /api/user/current | GET | 获取当前用户 |
| 用户 | /api/user/logout | POST | 退出登录 |
| 商品 | /api/product/{id} | GET | 查询商品详情 |
| 商品 | /api/product/{id}/stock | GET | 查询商品库存 |
| 商品 | /api/product/{id}/warmup | POST | 预热商品到缓存 |
| 秒杀 | /api/seckill/{productId} | POST | 秒杀下单 |
| 订单 | /api/order/{orderNo} | GET | 查询订单 |
| 订单 | /api/order/{orderNo}/cancel | POST | 取消订单 |
| 订单 | /api/order/my | GET | 我的订单 |
| 排行榜 | /api/rank/top | GET | Top N 排行榜 |
| 排行榜 | /api/rank/my | GET | 我的排名 |
| 答题 | /api/answer/submit | POST | 提交答题记录 |
| 测试 | /api/test/hello | GET | 黑名单/限流测试 |
| 黑名单 | /api/blacklist/remove | DELETE | 移除黑名单 |
| 黑名单 | /api/blacklist/list | GET | 查看黑名单列表 |

## 秒杀核心链路

```
请求进来
  ↓
布隆过滤器（拦截不存在的商品 ID，定时刷新兜底新增商品）
  ↓
用户防重（Redis SetIfAbsent 快速过滤 + DB 状态查询兜底）
  ↓
三级缓存查库存（Caffeine ProductDTO → Redis ProductDTO → MySQL）
  ↓
Redisson 分布式锁串行化
  ↓
Redis DECR 预扣库存（原子操作）
  ↓
MySQL 乐观锁扣库存（version 兜底，失败回滚 Redis 预扣库存）
  ↓
创建订单（编程式事务，Redis 防重 + DB 防重双重检查）
  ↓
发送延时消息（30 分钟未支付自动取消，失败加入 Redis 重试队列）
  ↓
第 1 层：RabbitMQ 死信延时 → OrderCancelConsumer
  ↓
第 2 层：DelayRetryService 重试队列 → 死信队列 → 兜底取消
  ↓
第 3 层：OrderReconcileService 每 60s 扫 DB 兜底取消
```

## Redis Key 设计规范

| 业务场景 | Key 格式 | 说明 |
|----------|----------|------|
| 商品库存缓存 | `seckill:stock:{productId}` | 实时计数器，DECR 预扣/INCR 回滚 |
| 商品详情缓存 | `product:{id}` | 三级缓存 Redis 层，存储 ProductDTO |
| 秒杀分布式锁 | `seckill:lock:{productId}` | Redisson 分布式锁 |
| 用户抢购记录 | `seckill:user:{productId}:{userId}` | 防重复抢购，TTL 1h |
| 延时消息重试队列 | `seckill:delay:retry` | 延时消息发送失败后重试 |
| 延时消息重试次数 | `seckill:delay:retry:cnt:{orderNo}` | 重试计数，TTL 2h，上限 5 次 |
| 延时消息死信队列 | `seckill:delay:dead` | 超过重试次数，需人工兜底 |
| 死信消息重试队列 | `seckill:dead:retry` | 死信补偿重试 |
| 布隆过滤器 | `bloom:product` | 商品 ID 布隆过滤器，启动时预热 |
| 积分排行榜 | `rank:score` | ZSet，score 为积分 |
| 用户缓存 | `user:{id}` | 三级缓存 Redis 层，存储 UserDTO |
| 用户缓存失效广播 | `cache:invalidate:user` | Redis Pub/Sub Channel |
| IP 限流 | `ratelimit:ip:{ip}` | ZSet 滑动窗口 |
| IP 黑名单 | `blacklist:ip` | Set，被拉黑的 IP 集合 |
| 用户黑名单 | `blacklist:user` | Set，被拉黑的用户集合 |
| IP 违规计数 | `blacklist:count:ip:{ip}` | 违规次数，TTL 10min |
| 用户违规计数 | `blacklist:count:user:{userId}` | 违规次数，TTL 10min |
| 答题异步队列 | `answer:queue` | Redis List 主队列 |
| 答题处理中队列 | `answer:processing` | Redis List 处理中队列 |
| 缓存失效广播 | `cache:invalidate` | Redis Pub/Sub Channel |

## 压测基线

| 接口 | 并发数 | 预期 QPS | 预期 P99 | 校验指标 |
|------|--------|----------|----------|----------|
| 秒杀下单 | 1000 | ≥ 800 | < 200ms | 零超卖 |
| 商品查询 | 2000 | ≥ 3000 | < 50ms | 缓存命中率 > 95% |
| 限流验证 | 5000 | 返回 429 | - | 超窗口请求全部限流 |
| 排行榜查询 | 1000 | ≥ 2000 | < 30ms | 返回正确 Top10 |

## 设计亮点（面试向）

### 1. 库存零泄漏的三层安全网
```
第 1 层：RabbitMQ 延时消息（主路径）→ 发消息失败进入第 2 层
第 2 层：Redis 重试队列 + 死信队列（补偿）→ 兜底取消进入第 3 层
第 3 层：DB 全局对账（终极兜底）→ 每 60s 扫超时未支付订单取消
```

### 2. 三级缓存语义统一
- 商品详情缓存仅存储 `ProductDTO`（不含 `stock`/`version`），库存独立走 `seckill:stock:{id}` 实时计数器
- 用户缓存使用 `UserDTO`（不含 `password`），天然脱敏
- 缓存失效通过 Pub/Sub 广播，多实例保持一致性

### 3. 秒杀防重双保险
- Redis SetIfAbsent 快速过滤（TTL 1h）
- DB 状态查询兜底（`WHERE status IN (0,1)`），解决 Redis 过期漏洞
- 已取消订单允许重新秒杀，不影响用户体验

### 4. 布隆过滤器的原子刷新
- `volatile` 实例引用 + 构建新实例后原子替换（build-then-swap）
- 定时刷新（5min）+ 即时补位（put）双触发
- 未就绪时静默放行，不阻塞业务