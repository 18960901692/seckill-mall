# 基于 Redis 的高并发学习商城秒杀系统

> 以 Redis 为核心实现缓存三大问题治理、Redisson 分布式锁防超卖、AOP+Lua 滑动窗口限流、ZSet 积分排行榜；搭配 MySQL 乐观锁做库存最终兜底，通过事务+唯一索引保证下单幂等性；使用 RabbitMQ 延时队列实现超时订单自动回滚。

## 技术架构

```mermaid
flowchart TD
    U[用户请求] --> Nginx[Nginx / 多 Tomcat 实例]
    Nginx -->|分布式Session| Redis[(Redis)]
    Nginx --> C[Controller 层]

    C --> AOP[@RateLimit AOP 滑动窗口限流]
    AOP -->|本地二级限流| SEM[JUC Semaphore]
    SEM -->|分布式一级限流| RL[Redis ZSet 滑动窗口]

    C --> SVC[SeckillService 核心秒杀]
    SVC -->|防穿透| BF[Guava 布隆过滤器]
    SVC -->|三级缓存读库存| CACHE[Caffeine→Redis→MySQL]
    SVC -->|防击穿/雪崩| HOT[热点不过期 + TTL 随机]
    SVC -->|防超卖 第一层| LOCK[Redisson 分布式锁]
    SVC -->|扣库存| RDEC[Redis DECR 预扣]

    RDEC -->|异步落库| MQr[RabbitMQ 延时队列]
    SVC -->|防超卖 最终兜底| OPT[MySQL 乐观锁 version]
    SVC -->|下单| TX[@Transactional + 唯一索引幂等]
    TX --> DB[(MySQL: product / orders / user)]
    SVC -->|答题记录异步| LIST[Redis List → 线程池落库]
    SVC -->|积分排行| ZSET[Redis ZSet 排行榜]

    MQr -->|30min 未支付| CANCEL[释放库存: Redis回滚 + MySQL回补]
    CACHE -.多实例一致性.-> PUBSUB[Redis Pub/Sub 广播失效]

    classDef red fill:#fde8e3,stroke:#e0532e;
    classDef blue fill:#e3edff,stroke:#4084ff;
    class RL,LOCK,RDEC,ZSET,LIST,PUBSUB red;
    class OPT,TX,DB blue;
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

### Redis 核心
- **三级缓存**：Caffeine → Redis → MySQL，减少 DB 压力
- **缓存三大问题治理**：
  - 穿透：Guava 布隆过滤器预热全量商品ID
  - 击穿：热点商品永不过期 + Redisson 互斥锁重建
  - 雪崩：TTL 随机抖动（30min ± 5min）
- **多实例缓存一致性**：Redis Pub/Sub 广播失效通知

### 并发控制
- **双层防超卖**：Redisson 分布式锁（第一层）+ MySQL 乐观锁 version（兜底）
- **双重限流**：Semaphore 本地限流（单机100并发）+ Redis ZSet 滑动窗口分布式限流
- **用户防重**：Redis Set 记录已抢用户，同一用户同一商品只能抢一次

### 消息可靠性
- **RabbitMQ 延时队列**：死信队列 + TTL 实现 30 分钟未支付自动取消
- **消息可靠性三板斧**：Confirm 确认 + 持久化 + 手动 ACK
- **消费幂等**：订单状态判断，已处理直接 ACK

### 安全与鉴权
- **BCrypt 密码加密**
- **登录拦截器**：未登录请求返回 401
- **密码脱敏**：返回 User 前清空 password 字段

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
| 答题 | /api/answer/batch-save | POST | 批量落库答题记录 |

## 秒杀核心链路

```
请求进来
  ↓
布隆过滤器（拦截不存在的商品ID）
  ↓
用户防重（Redis Set，同一用户只能抢一次）
  ↓
三级缓存查库存（Caffeine → Redis → MySQL，快速失败）
  ↓
Redisson 分布式锁串行化
  ↓
Redis DECR 预扣库存（原子操作）
  ↓
MySQL 乐观锁扣库存（version 兜底，失败回滚 Redis）
  ↓
创建订单（唯一索引保证幂等）
  ↓
发送延时消息（30分钟未支付自动取消）
```

## Redis Key 设计规范

| 业务场景 | Key 格式 | 说明 |
|----------|----------|------|
| 商品库存缓存 | `seckill:stock:{productId}` | 三级缓存 Redis 层 |
| 秒杀分布式锁 | `seckill:lock:{productId}` | Redisson 锁 |
| 用户抢购记录 | `seckill:user:{productId}:{userId}` | 防重复抢购，TTL 1h |
| 积分排行榜 | `rank:score` | ZSet，score 为积分 |
| IP 限流 | `ratelimit:ip:{ip}` | ZSet 滑动窗口 |
| 答题异步队列 | `answer:queue` | Redis List |
| 缓存失效广播 | `cache:invalidate` | Redis Pub/Sub Channel |

## 压测基线

| 接口 | 并发数 | 预期 QPS | 预期 P99 | 校验指标 |
|------|--------|----------|----------|----------|
| 秒杀下单 | 1000 | ≥ 800 | < 200ms | 零超卖 |
| 商品查询 | 2000 | ≥ 3000 | < 50ms | 缓存命中率 > 95% |
| 限流验证 | 5000 | 返回 429 | - | 超窗口请求全部限流 |
| 排行榜查询 | 1000 | ≥ 2000 | < 30ms | 返回正确 Top10 |
