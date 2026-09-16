# 基于 Redis 的高并发学习商城秒杀系统

> 一个**可直接运行、可压测、可面试讲**的秒杀实战项目。以 Redis 为核心实现缓存三大问题治理、Redisson 分布式锁防超卖、AOP + Lua 滑动窗口限流、ZSet 积分排行榜；用 MySQL 乐观锁做库存最终兜底，编程式事务保证下单幂等；再用 RabbitMQ 延时队列 + **三层补偿安全网**实现超时订单自动回滚，做到**库存零泄漏**。

<p align="left">
  <img alt="Java" src="https://img.shields.io/badge/Java-17-orange">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.3.2-brightgreen">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-7-red">
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.0-blue">
  <img alt="RabbitMQ" src="https://img.shields.io/badge/RabbitMQ-3.x-ff69b4">
  <img alt="Docker" src="https://img.shields.io/badge/Docker-Compose-2496ED">
</p>

---

## 目录

- [一、项目简介](#一项目简介)
- [二、技术栈](#二技术栈)
- [三、系统架构](#三系统架构)
- [四、核心功能](#四核心功能)
- [五、项目结构](#五项目结构)
- [六、快速开始](#六快速开始)
  - [6.1 方式一：本地运行](#61-方式一本地运行)
  - [6.2 方式二：Docker 一键部署（推荐）](#62-方式二docker-一键部署推荐)
  - [6.3 冒烟测试（复制即用）](#63-冒烟测试复制即用)
- [七、配置说明](#七配置说明)
- [八、接口列表](#八接口列表)
- [九、秒杀核心链路](#九秒杀核心链路)
- [十、Redis Key 设计规范](#十redis-key-设计规范)
- [十一、定时任务清单](#十一定时任务清单)
- [十二、数据库设计](#十二数据库设计)
- [十三、压测基线](#十三压测基线)
- [十四、设计亮点（面试向）](#十四设计亮点面试向)
- [十五、常见问题 FAQ](#十五常见问题-faq)
- [十六、配套学习资料](#十六配套学习资料)

---

## 一、项目简介

秒杀系统的难点不在于"把订单写进数据库"，而在于**高并发下的三个"不能"**：

| 问题 | 后果 | 本项目对策 |
|------|------|-----------|
| 超卖 | 100 件商品卖出 120 单，公司赔钱 | Redisson 分布式锁（第一层）+ MySQL 乐观锁 version（兜底） |
| 库存泄漏（少卖） | 用户下单未支付，库存被永久占用 | RabbitMQ 延时消息 + 重试补偿 + DB 全局对账，**三层安全网** |
| 打垮 DB | 流量直接穿透到 MySQL，数据库被打挂 | 布隆过滤器 + 三级缓存 + 滑动窗口限流 + 自动黑名单 |

项目同时包含**用户 / 商品 / 秒杀 / 订单 / 排行榜 / 答题 / 黑名单 / 管理员**八个业务模块，覆盖缓存、锁、MQ、限流、异步落库、对账等后端高频考点，代码量约 **4500 行**（68 个 Java 类 + 4 个 Mapper XML），适合作为后端面试的项目实战素材。

---

## 二、技术栈

| 分类 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言/运行时 | Java | 17 | Records、文本块、虚拟线程前的现代 Java |
| 基础框架 | Spring Boot | 3.3.2 | 自动装配、内嵌 Tomcat |
| ORM | MyBatis-Plus | 3.5.7 | 单表 CRUD + 条件构造器 + 乐观锁插件 |
| 数据库 | MySQL | 8.0+ | 商品、订单、用户、答题记录 |
| 连接池 | HikariCP | Boot 内置 | 数据库连接池 |
| 缓存 | Redis (Lettuce) | 6.0+ / 镜像 7-alpine | 分布式缓存、计数器、排行榜、限流、队列 |
| 本地缓存 | Caffeine | Boot 管理 | 一级缓存（进程内） |
| 分布式锁 | Redisson | 3.32.0 | 可重入锁、看门狗续期 |
| 消息队列 | RabbitMQ | 3.10+ | 死信队列 + TTL 实现延时消息 |
| 布隆过滤器 | Guava | 33.2.0-jre | 防缓存穿透 |
| 分布式 Session | Spring Session Data Redis | Boot 管理 | 多实例共享登录态 |
| 密码加密 | Spring Security Crypto | Boot 管理 | 仅引入 BCrypt，不引入整套 Security |
| 接口文档 | Knife4j (OpenAPI3) | 4.4.0 | 在线调试 `doc.html` |
| 监控 | Spring Boot Actuator | Boot 管理 | `/actuator/health` 健康检查 |
| 容器化 | Docker / Docker Compose | - | 多阶段构建 + 一键编排 |
| 压测 | Apache JMeter | 5.6+ | 秒杀/查询/限流/排行榜四场景 |
| 工具 | Lombok | Boot 管理 | 简化样板代码 |

---

## 三、系统架构

```
                                    ┌─ 用户请求 ─┐
                                    └─────┬──────┘
                                          │
                          ┌───────────────┼───────────────┐
                          ▼               ▼               ▼
                    ┌──────────┐   ┌───────────┐   ┌───────────┐
                    │  Nginx   │   │ RateLimit │   │ BlackList │
                    │ 多实例   │   │ Semaphore │   │ 拦截器    │
                    └────┬─────┘   │+ ZSet滑动 │   │ 违规5次   │
                         │         │ 窗口限流  │   │ 自动拉黑  │
                         │         └───────────┘   └───────────┘
                         ▼
                    ┌──────────────────────────────────────────────┐
                    │              SeckillService                  │
                    │                                              │
                    │  ① 布隆过滤器（防穿透，5min 定时刷新）          │
                    │  ② 三级缓存查库存: Caffeine→Redis→MySQL       │
                    │  ③ Redisson 分布式锁（防超卖第一层）           │
                    │  ④ Redis DECR 预扣库存（原子操作）             │
                    │  ⑤ MySQL 乐观锁 version（防超卖兜底）          │
                    │  ⑥ 编程式事务 + 防重双检查（Redis + DB）       │
                    │  ⑦ 发送延时消息（RabbitMQ TTL 30min）          │
                    └───────────────────────┬──────────────────────┘
                                          │
              ┌───────────────────────────┼───────────────────────────┐
              ▼                           ▼                           ▼
        ┌───────────┐           ┌──────────────────┐       ┌──────────────────┐
        │  MySQL    │           │   Redis 缓存层    │       │  RabbitMQ 消息   │
        │          │           │                  │       │                  │
        │ product  │           │ product:{id}     │       │ 延时队列 30min   │
        │ orders   │           │  → ProductDTO    │       │     ↓ (TTL 到期)  │
        │ user     │           │ seckill:stock:{} │       │ 死信→取消队列     │
        │ answer_  │           │  → 实时计数器     │       │ OrderCancelCons  │
        │ record   │           │ user:{id}        │       │  ─── 第1层 ────  │
        │          │           │  → UserDTO       │       └────────┬─────────┘
        │ 真相源    │           │ rank:score ZSet  │               │
        │ Source   │           │ answer:queue     │               │ 发送/消费失败
        │ of Truth │           │ blacklist:ip/set │               ▼
        └─────▲────┘           │ ratelimit:ip:{}  │       ┌──────────────────┐
              │                └──────────────────┘       │ 第2层：补偿重试  │
              │                                            │ DelayRetryService│
              │  ┌──────────── 每 60s 扩散扫描 ────────────│ (30s) + 死信重试 │
              │  │                                          │ (60s/5min) 上限5次│
              │  ▼                                          └────────┬─────────┘
        ┌──────────────────┐                                        │ 仍失败
        │ 第3层：全局对账  │◀──────── 最终兜底 ─────────────────────┘
        │ StockReconcile   │
        │ OrderReconcile   │
        │ 每 60s 扫 DB 超时 │
        │ 未支付订单并取消  │
        └──────────────────┘
```

**分层说明**

| 层 | 组件 | 职责 |
|----|------|------|
| 接入层 | 黑名单拦截器 → 登录拦截器 → 管理员拦截器 | 按 `order` 顺序串联，先拒绝恶意请求，再校验身份 |
| 限流层 | `@RateLimit` AOP + Lua 脚本 | Redis ZSet 滑动窗口，原子执行，秒杀接口 10000 次/秒 |
| 业务层 | SeckillService / OrderService / ... | 防重、扣减、下单、事务 |
| 缓存层 | Caffeine → Redis → MySQL | 三级回源，Pub/Sub 广播失效 |
| 消息层 | RabbitMQ + Redis 补偿队列 | 延时取消 + 失败重试 |
| 兜底层 | StockReconcile / OrderReconcile | 以 MySQL 为准，定时对账纠偏 |

---

## 四、核心功能

### 4.1 Redis 缓存治理

- **三级缓存**：Caffeine（本地）→ Redis（分布式）→ MySQL（DB），从顶向下逐层回源
- **缓存三大问题治理**：
  - **穿透**：Guava 布隆过滤器预热全量商品 ID（预期容量 10000、误判率 1%），启动预热 + 5 分钟定时全量刷新 + 新增商品即时 `put()` 补位
  - **击穿**：热点商品永不过期（`hot=1`）+ Redisson 互斥锁双重检查
  - **雪崩**：TTL 随机抖动（30min ± 5min）
- **多实例缓存一致性**：Redis Pub/Sub 广播失效通知，各实例收到后清除本地 Caffeine 缓存

### 4.2 用户缓存治理

- **用户三级缓存**：Caffeine → Redis → MySQL，与商品缓存架构保持一致
- **数据脱敏**：缓存与接口返回统一使用 `UserDTO`（仅 id、username、createTime），**不含 password**；Session 中只存 `userId`
- **分布式锁防击穿**：Redisson 锁 + 双重检查，高并发查询用户不压垮 DB
- **多实例一致性**：独立 Pub/Sub 频道 `cache:invalidate:user` 广播用户缓存失效

### 4.3 并发控制

- **双层防超卖**：Redisson 分布式锁（第一层，`tryLock(3, 10, SECONDS)`，看门狗续期）+ MySQL 乐观锁 `version`（兜底）
- **双重限流**：Semaphore 本地限流（单机快速降级）+ Redis ZSet 滑动窗口分布式限流（Lua 保证"判断 + 计数"原子性）
- **用户防重**：Redis `SetIfAbsent` 快速过滤（TTL 1h）+ DB 状态查询兜底（`status IN (0,1)`），双重保障防止重复下单；**已取消订单允许重新秒杀**

### 4.4 订单状态机与支付

```
            下单成功
               │
               ▼
        ┌─────────────┐   支付（模拟，写 transaction_id）
        │ 0 未支付     │ ──────────────────────────▶  ┌─────────────┐
        └──────┬──────┘                              │ 1 已支付     │
               │                                     └─────────────┘
               │ 超时 30min / 用户主动取消
               ▼
        ┌─────────────┐
        │ 2 已取消     │ ──▶ 事务内 WHERE status=0 条件更新，保证幂等
        └─────────────┘     仅回滚 MySQL 库存，Redis 由对账每 60s 以 MySQL 为准 SET 校正
```

- 状态流转统一使用**条件更新**（`WHERE status=0`），并发下天然幂等
- `uk_transaction_id` 唯一索引保证支付幂等
- 订单 `amount` 为**下单时快照**，商品后期调价不影响历史订单

### 4.5 订单取消三层安全网

| 层级 | 组件 | 触发时机 | 说明 |
|------|------|----------|------|
| 第 1 层（主路径） | RabbitMQ 死信延时队列 | 下单 30 分钟后 | 延时队列 TTL 到期 → 死信交换机 → 取消队列 → `OrderCancelConsumer` |
| 第 2 层（补偿重试） | `DelayRetryService` + `DeadLetterRetryService` | 第 1 层发送/消费失败 | Redis 重试队列（`seckill:delay:retry`）每 30s 重发，上限 5 次后转入死信队列；死信补偿队列每 60s 重投；**绝不静默丢弃** |
| 第 3 层（全局对账） | `OrderReconcileService` | 每 60 秒 | 直接扫 DB「`status=0` 且超时」订单逐笔兜底取消——**无论 MQ/Redis 链路是否正常** |

### 4.6 库存对账（最终兜底）

- **定时对账**：每 60 秒批量查询商品库存，与 Redis 对比
- **分布式锁保护**：复用秒杀同一把锁 `seckill:lock:{productId}`，确保与秒杀/对账的 Redis 操作串行
- **锁内重查 MySQL**：循环外快照只负责遍历，真正校正前**锁内重查 `selectById`** 拿新鲜值，避免"循环外读 MySQL + 锁内只用 Redis 双检"导致用旧值 SET 覆盖秒杀刚提交的正确值（详见 INTERVIEW_QUESTIONS 第 58 题）
- **以 DB 为准**：Redis 与 MySQL 不一致时以锁内查到的新鲜 MySQL 值校正 Redis 库存
- **三层兜底体系定位**：代码异常补偿（JVM 正常）→ MQ/Redis 补偿队列（临时故障）→ 库存/订单对账（JVM 宕机、断电等代码补偿无法覆盖的极端场景）

### 4.7 消息可靠性

- **RabbitMQ 延时队列**：`DirectExchange` + `x-message-ttl=1800000`(30min) + `x-dead-letter-exchange` 实现延时消息
- **发送端同步确认**：`OrderDelayProducer` 用 `publisher-confirm-type: correlated`，发送时携带 `CorrelationData` + `Future.get(3s)` 同步等 Broker 确认；同时校验 `Returned` 路由退回，NACK/路由失败/超时统一抛异常流入 Redis 补偿队列
- **三板斧**：队列/消息持久化 + `acknowledge-mode: manual` 手动 ACK
- **消费幂等**：`WHERE status=0` 条件更新，已支付/已取消订单直接跳过
- **补偿队列自身防丢**：`seckill:dead:retry` 用 `RPOPLPUSH` 原子迁移至 processing，重投成功才删除；应用宕机残留由下轮 `recoverProcessing` 恢复

### 4.8 黑名单与安全

- **自动限流拉黑**：10 分钟窗口内违规 5 次自动加入黑名单（Redis Set），后续请求直接 403
- **IP 可信代理边界**：`IpUtils` 先以 TCP 层 `remoteAddr` 判断是否属于配置的可信代理（CIDR 支持），不可信直连一律不采信 XFF/X-Real-IP，杜绝伪造 IP 绕过限流或嫁祸拉黑
- **三级拦截器**：黑名单拦截器（`order=0`）→ 登录拦截器（`order=1`）→ 管理员拦截器（`order=2`，路径 `/api/admin/**` + `/api/blacklist/**`，需 `@RequireAdmin` 注解）
- **黑白名单管理 API**：支持查看、手动加入、移除黑名单
- **BCrypt 密码加密**；登录态存 Redis Session，多实例共享；login 时显式清除非 admin 账号的 `isAdmin` 标志位，防止残留越权
- **数据脱敏**：所有接口返回 `UserDTO`，不暴露 password
- **敏感配置抽离**：DB/Rabbit 密码等通过环境变量注入或 `.env` 文件读取，`application.yaml` 不再硬编码明文默认值

### 4.9 布隆过滤器

- **启动预热**：`@PostConstruct` 建实例 + `CommandLineRunner` 全量加载商品 ID
- **定时刷新**：每 5 分钟从 DB 全量重建，`volatile` 引用 + build-then-swap **原子替换**
- **即时补位**：新增商品调用 `put()` 立即写入，无需等定时刷新
- **防御性编程**：过滤器未就绪时放行所有请求，避免初始化窗口误拦截

### 4.10 答题异步与排行榜

- **异步批量落库**：Redis List 收集答题记录，每 5 秒定时批量写入 DB（`bizExecutor` 线程池）
- **幂等写入**：`uk_user_question` 唯一索引 + `ON DUPLICATE KEY UPDATE`，同一用户同一题目不重复记录
- **可靠队列**：`RPOPLPUSH` 原子转移至 `answer:processing` 处理队列，落库成功后再删除；启动时恢复残留消息
- **答题防重（首答判重）**：入队前 `SADD answered:{userId} {questionId}`，已答过直接返回 3001；首答判定同时覆盖记录与积分两个副作用，杜绝"同一题刷 1000 次 = +10000 分"的漏洞
- **积分排行榜**：答题正确得 10 分，写入 Redis ZSet（`rank:score`），支持 Top N 与个人排名

---

## 五、项目结构

```
seckill-mall
├── src/main/java/com/sygzcd/seckillmall
│   ├── SeckillMallApplication.java      # 启动类（@EnableScheduling @EnableAsync）
│   ├── annotation/
│   │   └── RequireAdmin.java            # 管理员权限注解（配合 AdminInterceptor）
│   ├── aop/
│   │   ├── annotation/RateLimit.java    # 限流注解：windowSec / maxCount / keyPrefix
│   │   ├── RateLimitAspect.java         # 限流切面：Redis ZSet + Lua 滑动窗口
│   │   └── LogAspect.java               # 请求日志切面
│   ├── common/                          # Result / ResultCode / BusinessException
│   │   ├── Result.java                  # 统一返回体 {code, message, data}
│   │   ├── ResultCode.java              # 完整业务码（200/4xx/5xx + 10xx/20xx/30xx）
│   │   ├── GlobalExceptionHandler.java  # 全局异常处理
│   │   ├── ProductDTO.java              # 商品缓存对象（不含 stock/version）
│   │   ├── UserDTO.java                 # 用户脱敏对象（不含 password）
│   │   ├── PayResultDTO.java            # 支付结果
│   │   └── util/IpUtils.java            # IP 可信代理边界（CIDR 支持 + fail-closed）
│   ├── config/
│   │   ├── CaffeineConfig.java          # 本地缓存（Caffeine）
│   │   ├── CacheInvalidateConfig.java   # Pub/Sub 缓存失效订阅
│   │   ├── RabbitMQConfig.java          # 延时/取消交换机、队列、TTL、DLX
│   │   ├── RedisConfig.java             # RedisTemplate 序列化配置
│   │   ├── RedissonConfig.java          # Redisson 客户端
│   │   ├── SessionConfig.java           # Spring Session Redis
│   │   ├── ThreadPoolConfig.java        # bizExecutor 业务线程池（禁止 new Thread）
│   │   ├── MybatisPlusConfig.java       # 乐观锁插件 + 分页插件
│   │   ├── Knife4jConfig.java           # OpenAPI3 文档
│   │   ├── StockWarmUpRunner.java       # 启动预热 Redis 商品库存
│   │   └── WebMvcConfig.java            # 三级拦截器注册
│   ├── controller/                      # 9 个 Controller，22 个接口
│   │   ├── AdminProductController.java      # 管理员商品属性更新（唯一真实触发 invalidateCache 的入口）
│   │   ├── UserController / ProductController / SeckillController
│   │   ├── OrderController / RankController / AnswerController
│   │   ├── BlackListController / TestController
│   ├── entity/                          # Product / Orders / User / AnswerRecord
│   ├── interceptor/                     # BlackList / Auth / Admin 拦截器
│   ├── mapper/                          # 4 个 Mapper 接口
│   └── service/
│       ├── impl/
│       │   ├── SeckillServiceImpl.java      # ★ 秒杀核心链路
│       │   ├── OrderServiceImpl.java        # 订单状态机（不再 INCR Redis 库存）
│       │   ├── ProductServiceImpl.java      # 三级缓存 + getStock 懒加载回填
│       │   ├── UserServiceImpl.java         # 登录/注册（login 显式清除 isAdmin）
│       │   ├── UserCacheService.java        # 用户三级缓存
│       │   ├── BloomFilterServiceImpl.java  # 布隆过滤器（预热/刷新/补位）
│       │   ├── StockReconcileService.java   # 库存对账（60s，锁内重查 MySQL）
│       │   ├── OrderReconcileService.java   # 订单全局对账（60s，终极兜底）
│       │   ├── AnswerAsyncServiceImpl.java  # 答题异步落库 + SADD 首答判重
│       │   ├── RankServiceImpl.java         # ZSet 排行榜
│       │   └── BlackListServiceImpl.java    # 黑名单/违规计数
│       ├── mq/
│       │   ├── OrderDelayProducer.java      # 发延时消息（同步 Confirm + Return 校验）
│       │   ├── OrderCancelConsumer.java     # 消费取消订单（失败先落 Redis 再 ACK）
│       │   ├── DelayRetryService.java       # 延时消息重试（30s / 5min 死信）
│       │   └── DeadLetterRetryService.java  # 死信补偿重投（60s，RPOPLPUSH 保护）
├── src/main/resources
│   ├── application.yaml                 # 本地环境配置
│   ├── application-docker.yaml          # Docker 环境配置（服务名寻址）
│   └── mapper/*.xml                     # 4 个 Mapper XML（动态 SQL）
├── sql/schema.sql                       # 建库建表 + 索引 + 初始数据
├── jmeter_test/                         # JMeter 压测脚本与测试数据
├── 文档/                                 # 配套学习文档（本地资料）
├── Dockerfile                           # 多阶段构建（Maven → JRE Alpine，非 root）
├── docker-compose.yml                   # MySQL + Redis + RabbitMQ + App 编排
├── .env.example                         # 环境变量占位模板
└── .env                                 # 真实环境变量（不入库）
```

---

## 六、快速开始

### 6.1 方式一：本地运行

**1) 环境准备**

| 依赖 | 版本要求 | 默认端口 |
|------|----------|----------|
| JDK | 17+ | - |
| Maven | 3.8+（或用自带 `mvnw`） | - |
| MySQL | 8.0+ | 3306（若用 compose 则映射 3307） |
| Redis | 6.0+ | 6379 |
| RabbitMQ | 3.10+ | 5672 / 控制台 15672 |

> ⚠️ RabbitMQ 必须使用 `admin/admin123` 账号，**不要用 guest**（guest 默认仅允许 localhost 且项目配置已适配 admin）。

**2) 初始化数据库**

```bash
mysql -u root -p < sql/schema.sql
```

脚本会自动创建 `seckill_mall` 库、4 张表、8 个二级索引，并插入 1 个秒杀商品和 1 个管理员账号。

**3) 修改配置**

编辑 `src/main/resources/application.yaml`，按本机情况修改数据库密码、Redis 地址、RabbitMQ 账号。也可通过环境变量注入，避免改代码：

```bash
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=你的密码
export RABBITMQ_USERNAME=admin
export RABBITMQ_PASSWORD=admin123
```

**4) 启动**

```bash
./mvnw spring-boot:run
# 或
mvn spring-boot:run
# 或先打包
mvn clean package -DskipTests && java -jar target/seckill-mall-0.0.1-SNAPSHOT.jar
```

**5) 访问**

- 接口文档（Knife4j）：<http://localhost:8080/doc.html>
- 健康检查：<http://localhost:8080/actuator/health>

### 6.2 方式二：Docker 一键部署（推荐）

一键拉起 MySQL 8.0 + Redis 7 + RabbitMQ 3 + 应用 四个容器，无需在本机安装任何中间件。

```bash
# 1. 准备环境变量（首次执行）
cat > .env <<'EOF'
MYSQL_USERNAME=root
MYSQL_ROOT_PASSWORD=18765105
RABBITMQ_USERNAME=admin
RABBITMQ_PASSWORD=admin123
EOF

# 2. 构建并启动全部服务
docker compose up -d --build

# 3. 查看容器状态（等待 4 个服务 healthy）
docker compose ps

# 4. 跟踪应用日志
docker compose logs -f app
```

**启动顺序由 `depends_on: condition: service_healthy` 保证**：应用会等到 MySQL、Redis、RabbitMQ 三个健康检查都通过后才启动，避免"启动即报连接失败"。

| 服务 | 容器名 | 本机端口 | 说明 |
|------|--------|----------|------|
| MySQL | seckill-mysql | 3307 → 3306 | 首次启动自动执行 `sql/schema.sql` |
| Redis | seckill-redis | 6379 | `maxmemory 256mb`，`allkeys-lru` 淘汰 |
| RabbitMQ | seckill-rabbitmq | 5672 / 15672 | 控制台 <http://localhost:15672>，admin/admin123 |
| App | seckill-app | 8080 | 多阶段构建，非 root 用户运行 |

**常用运维命令**

```bash
docker compose ps                      # 查看状态
docker compose logs -f app             # 应用日志
docker compose restart app             # 重启应用
docker compose down                    # 停止并删除容器（保留数据卷）
docker compose down -v                 # ⚠️ 连同数据卷一起删除（清空数据库）
```

> Dockerfile 采用**多阶段构建**：第一阶段用 `maven:3.9-eclipse-temurin-17` 编译打包（先 COPY pom.xml 利用缓存层，依赖不变则不重新下载），第二阶段只复制 jar 到 `eclipse-temurin:17-jre-alpine`，并以非 root 用户 `spring` 运行，镜像体积和攻击面都更小。

### 6.3 冒烟测试（复制即用）

按顺序执行，验证核心链路是否跑通：

```bash
BASE=http://localhost:8080

# 1. 注册
curl -s -X POST "$BASE/api/user/register?username=tom&password=123456"

# 2. 登录（-c 保存 Cookie）
curl -s -c cookie.txt -X POST "$BASE/api/user/login?username=tom&password=123456"

# 3. 查询商品（预热前会回源 DB 并写入缓存）
curl -s -b cookie.txt "$BASE/api/product/1001"

# 4. 查询库存
curl -s -b cookie.txt "$BASE/api/product/1001/stock"

# 5. ★ 秒杀下单（核心）
curl -s -b cookie.txt -X POST "$BASE/api/seckill/1001"

# 6. 再抢一次 → 应返回「重复下单」(code 1003)
curl -s -b cookie.txt -X POST "$BASE/api/seckill/1001"

# 7. 我的订单
curl -s -b cookie.txt "$BASE/api/order/my?page=1&size=10"

# 8. 提交答题（答对 +10 分）
curl -s -b cookie.txt -X POST "$BASE/api/answer/submit?questionId=1&correct=true"

# 9. 查看排行榜
curl -s -b cookie.txt "$BASE/api/rank/top?n=10"
```

预期结果：第 5 步返回订单号，第 6 步返回 `1003 重复下单`，第 8 步后排行榜出现当前用户 10 分。

---

## 七、配置说明

### 7.1 环境变量

所有敏感配置均通过环境变量或 `.env` 文件注入（**不硬编码在 yml 中**）。本地开发建议新建 `.env`（gitignore 已覆盖），Docker 部署由 compose 读取：

| 变量名 | 说明 | 默认/示例值 |
|--------|------|-------------|
| `MYSQL_USERNAME` | MySQL 用户名 | `root` |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `change_me`（本地 profile 用） |
| `MYSQL_DATABASE` | MySQL 数据库名 | `seckill_mall` |
| `RABBITMQ_USERNAME` | RabbitMQ 用户名 | `admin` |
| `RABBITMQ_PASSWORD` | RabbitMQ 密码 | `change_me` |
| `SPRING_PROFILES_ACTIVE` | 激活配置，Docker 下为 `docker` | `docker` |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx512m` |

> 本地 profile 中 `MYSQL_USERNAME` 保留默认值 `root`（用户名不算敏感），密码、Rabbit 账号密码都不带默认值；若未设置，应用启动会报连接失败——这是**有意的 fail-closed**。

### 7.2 两套 Profile 的差异

| 配置项 | `application.yaml`（本地） | `application-docker.yaml` |
|--------|---------------------------|---------------------------|
| MySQL 地址 | `localhost:3307` | `mysql:3306`（容器服务名） |
| Redis 地址 | `localhost:6379` | `redis:6379` |
| RabbitMQ 地址 | `localhost:5672` | `rabbitmq:5672` |
| 密码来源 | `${MYSQL_ROOT_PASSWORD:}` 无默认值 | `${MYSQL_ROOT_PASSWORD:}` 无默认值 |
| 可信代理 | `127.0.0.1,::1`（本机） | 空（按需配置容器网桥网段） |

> 容器间通过 **Docker 网络的服务名**互相寻址，因此 Docker profile 中不能写 `localhost`（那会指向容器自身）。`app.security.trusted-proxies` 默认留空（fail-closed），需按部署时 Nginx 所在网段手动配置（如 `172.16.0.0/12`）。

### 7.3 关键配置项

| 配置 | 值 | 说明 |
|------|-----|------|
| `server.port` | 8080 | 服务端口 |
| `hikari.maximum-pool-size` | 20 | 连接池上限 |
| `redis.lettuce.pool.max-active` | 16 | Redis 连接池（Lettuce 基于 Netty，连接可复用） |
| `rabbitmq.listener.simple.acknowledge-mode` | `manual` | 手动 ACK，消费成功才确认 |
| `rabbitmq.listener.simple.prefetch` | 10 | 单消费者预取条数，配合手动 ACK |
| `rabbitmq.publisher-confirm-type` | `correlated` | 开启发送方确认，异步回调 |
| `management.endpoints.web.exposure.include` | `health` | **只暴露健康检查**，不暴露 env/beans 等敏感端点 |

---

## 八、接口列表

### 8.1 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

### 8.2 业务状态码

| code | 含义 | code | 含义 |
|------|------|------|------|
| 200 | 成功 | 429 | 请求过于频繁（触发限流，真实 HTTP 429） |
| 400 | 参数错误 | 1001 | 秒杀失败 |
| 401 | 未登录（真实 HTTP 401） | 1002 | 商品已售罄 |
| 403 | 无权限 / 已被拉黑（真实 HTTP 403） | 1003 | 重复下单 |
| 404 | 资源不存在 | 1005 | 系统繁忙（锁竞争） |
| 500 | 服务器内部错误 | 1010 | 订单已支付或已取消 |
| - | - | 1011 | 订单状态已变更，请刷新重试 |
| - | - | 2001 | 用户名已存在 |
| - | - | 2002 | 用户名或密码错误 |
| - | - | 3001 | 该题已作答，请勿重复提交 |

> 业务异常走 body code（HTTP 统一 200），拦截器返回的认证/授权/限流异常走真实 HTTP 状态码（401/403/429），便于压测脚本按 HTTP 状态断言。

### 8.3 接口清单

**认证方式**：登录后 Session 写入 Redis，浏览器自动携带 Cookie；curl 用 `-b cookie.txt`。

| 模块 | 方法 | 路径 | 参数 | 权限 | 说明 |
|------|------|------|------|------|------|
| 用户 | POST | `/api/user/register` | `username`, `password` | 公开 | 注册（BCrypt 加密） |
| 用户 | POST | `/api/user/login` | `username`, `password` | 公开 | 登录，写入 Redis Session |
| 用户 | GET | `/api/user/current` | - | 登录 | 获取当前用户（UserDTO） |
| 用户 | POST | `/api/user/logout` | - | 登录 | 退出登录，清除 Session |
| 商品 | GET | `/api/product/{id}` | - | 登录 | 商品详情（三级缓存） |
| 商品 | GET | `/api/product/{id}/stock` | - | 登录 | 实时库存（Redis 计数器） |
| 商品 | POST | `/api/product/{id}/warmup` | - | 登录 | 手动预热商品 + 库存到 Redis |
| 秒杀 | POST | `/api/seckill/{productId}` | - | 登录 | **秒杀下单**，限流 10000 次/秒（真实 HTTP 429） |
| 订单 | GET | `/api/order/{orderNo}` | - | 登录 | 查询订单详情（仅本人） |
| 订单 | POST | `/api/order/{orderNo}/cancel` | - | 登录 | 取消未支付订单（仅回滚 MySQL，Redis 由对账校正） |
| 订单 | POST | `/api/order/{orderNo}/pay` | - | 登录 | 模拟支付，状态 0 → 1 |
| 订单 | GET | `/api/order/my` | `page`, `size` | 登录 | 我的订单（分页） |
| 排行榜 | GET | `/api/rank/top` | `n`（默认 10） | 登录 | Top N 排行榜 |
| 排行榜 | GET | `/api/rank/my` | - | 登录 | 我的排名与积分 |
| 答题 | POST | `/api/answer/submit` | `questionId`, `correct` | 登录 | 提交答题（**首答判重**：SADD `answered:{userId}`，同一题二次提交返回 3001；异步入队，定时每 5 秒批量落库） |
| 商品管理 | POST | `/api/admin/product/{id}/update` | `name`（≤128）, `price`（0.01~99999999.99，≤2 位小数）, `hot`（0/1） | **管理员** | 修改商品属性（唯一真实触发 `invalidateCache` 的入口） |
| 黑名单 | GET | `/api/blacklist/check` | `type`, `key` | 登录 | 检查是否在黑名单 |
| 黑名单 | GET | `/api/blacklist/list` | `type` | 登录 | 查看黑名单列表 |
| 黑名单 | POST | `/api/blacklist/add` | `type`, `key` | **管理员** | 手动加入黑名单 |
| 黑名单 | DELETE | `/api/blacklist/remove` | `type`, `key` | **管理员** | 移出黑名单 |
| 测试 | GET | `/api/test/hello` | - | 公开 | 限流 + 自动拉黑演示（10s 内限 5 次） |

> `type` 取值：`ip` 或 `user`。

---

## 九、秒杀核心链路

```
请求进来
  ↓
① 黑名单拦截器（order=0）—— 被拉黑直接 403，不消耗任何后端资源
  ↓
② 登录拦截器（order=1）—— 未登录返回 401
  ↓
③ @RateLimit 限流切面（Lua + ZSet 滑动窗口，原子）—— 超限返回 429
  ↓
④ 布隆过滤器 —— 拦截不存在的商品 ID（防穿透），未就绪时放行
  ↓
⑤ 用户防重 —— Redis SetIfAbsent 快速过滤 + DB 状态查询兜底
  ↓
⑥ 三级缓存查库存 —— Caffeine ProductDTO → Redis ProductDTO → MySQL
  ↓
⑦ Redisson 分布式锁 tryLock(3, 10, SECONDS) —— 同一商品串行化，防超卖第一层
  ↓
⑧ Redis DECR 预扣库存 —— 原子操作，返回 < 0 则回滚并返回「库存不足」
  ↓
⑨ MySQL 乐观锁扣库存 —— WHERE stock > 0 AND version = ? ，影响行数 0 则回滚 Redis 预扣
  ↓
⑩ 编程式事务创建订单 —— Redis 防重 + DB 防重双重检查，uk_order_no 唯一索引兜底
  ↓
⑪ 事务提交后释放锁（避免"锁释放早于事务提交"的超卖窗口）
  ↓
⑫ 发送延时消息 —— 失败则写入 Redis 重试队列（绝不丢单）
  ↓
第 1 层：RabbitMQ 死信延时（30min）→ OrderCancelConsumer 取消订单
  ↓
第 2 层：DelayRetryService 重试队列 / DeadLetterRetryService 死信补偿
  ↓
第 3 层：OrderReconcileService 每 60s 扫 DB 兜底取消
```

> **执行顺序**：`HandlerInterceptor` 的 `preHandle` 一定**先于** `@RateLimit` 切面执行——`DispatcherServlet` 是"先 `applyPreHandle`，再调用 handler（即被 AOP 代理的 Controller 方法）"。所以恶意请求会在进入限流逻辑之前就被黑名单挡掉，这也是把黑名单拦截器的 `order` 设为 0 的原因。

> **关键细节**：锁的释放必须在**事务提交之后**。现改为 `TransactionTemplate` 编程式事务，提交后再解锁。补偿逻辑严格绑定"Redis 确实扣过（`redisDeducted`）且订单未提交（`committed=false`）"两个标志位，**afterCommit（发延时消息）已移出锁临界区外的 try**，其异常不会触发库存回补，防止给已成交订单虚增库存。

---

## 十、Redis Key 设计规范

### 10.1 商品与库存

| Key 格式 | 类型 | TTL | 说明 |
|----------|------|-----|------|
| `product:{id}` | String | 30min ± 5min | 商品详情（ProductDTO，**不含 stock/version**），热点商品永不过期 |
| `seckill:stock:{productId}` | String | - | **实时库存计数器**，`DECR` 预扣 / `INCR` 回滚，以 MySQL 为准对账 |
| `lock:product:{id}` | - | - | 商品缓存重建的互斥锁（防击穿双重检查） |

### 10.2 秒杀与订单

| Key 格式 | 类型 | TTL | 说明 |
|----------|------|-----|------|
| `seckill:lock:{productId}` | Redisson Lock | 10s（看门狗续期） | 秒杀分布式锁，与库存对账**共用同一把锁** |
| `seckill:user:{productId}:{userId}` | String | 1h | 用户抢购防重标记（DB 兜底双检） |
| `seckill:delay:retry` | List | - | 延时消息发送失败重试队列（每 30s 消费，单批 50） |
| `seckill:delay:retry:cnt:{orderNo}` | String | 2h | 单订单重发次数，超过 5 次转死信 |
| `seckill:delay:dead` | List | - | 重试耗尽的死信队列（每 5min 兜底取消） |
| `seckill:dead:retry` | List | - | 死信消费失败的补偿队列（每 60s 重投） |

### 10.3 用户与会话

| Key 格式 | 类型 | TTL | 说明 |
|----------|------|-----|------|
| `user:{id}` | String | 30min ± 5min | 用户缓存（UserDTO，不含 password） |
| `lock:user:{id}` | - | - | 用户缓存重建互斥锁 |
| `login:session:{userId}` | String | Session 有效期 | 登录态（只存 userId，不存整个 User 对象） |
| `spring:session:*` | Hash | - | Spring Session Redis 命名空间 |

### 10.4 排行榜、答题与安全

| Key 格式 | 类型 | TTL | 说明 |
|----------|------|-----|------|
| `rank:score` | ZSet | - | 积分排行榜，score 为积分，member 为 userId |
| `answer:queue` | List | - | 答题记录主队列（`RPOPLPUSH` → 处理队列） |
| `answer:queue:processing` | List | - | 答题记录处理中队列（落库成功后才删除） |
| `answered:{userId}` | Set | - | 用户已答过的 questionId（**首答判重**，防无限刷分） |
| `ratelimit:ip:{ip}` | ZSet | windowSec | 通用限流滑动窗口 |
| `ratelimit:seckill:{ip}` | ZSet | 1s | 秒杀接口限流（10000 次/秒） |
| `ratelimit:test:{ip}` | ZSet | 10s | 测试接口限流（5 次/10s） |
| `blacklist:ip` / `blacklist:user` | Set | - | IP / 用户黑名单 |
| `blacklist:count:ip:{ip}` | String | 10min | IP 违规计数，达 5 次拉黑 |
| `blacklist:count:user:{userId}` | String | 10min | 用户违规计数，达 5 次拉黑 |

### 10.5 Pub/Sub 频道

| 频道 | 说明 |
|------|------|
| `cache:invalidate` | 商品缓存失效广播（多实例清 Caffeine） |
| `cache:invalidate:user` | 用户缓存失效广播 |

> **注意**：布隆过滤器是 **Guava 纯 JVM 内存实现**，不占用 Redis key。因此每个实例各持一份过滤器，靠"启动预热 + 5 分钟定时全量刷新 + 新增即时补位"三者保持一致，属于**可接受的弱一致**设计（误判只影响新增商品的极短窗口）。

---

## 十一、定时任务清单

| 任务 | 实现类 | 周期 | 作用 |
|------|--------|------|------|
| 布隆过滤器全量刷新 | `BloomFilterServiceImpl` | 5 min | 从 DB 重建并**原子替换**实例 |
| 库存对账 | `StockReconcileService` | 60 s | 以 MySQL 校正 Redis 库存（复用秒杀同一把锁 + **锁内重查 MySQL** 拿新鲜值） |
| 订单全局对账 | `OrderReconcileService` | 60 s | 扫 `status=0` 超时订单兜底取消，单批 100 |
| 答题异步落库 | `AnswerAsyncServiceImpl` | 5 s | Redis List → DB 批量写入 |
| 延时消息重试 | `DelayRetryService` | 30 s | 重发失败延时消息，单批 50，超 5 次转死信 |
| 死信兜底取消 | `DelayRetryService` | 5 min | 扫 `seckill:delay:dead`，超时未支付直接取消 |
| 死信补偿重投 | `DeadLetterRetryService` | 60 s | `seckill:dead:retry` 重新投递到取消队列 |

> 所有任务统一使用 `fixedDelay`（上一轮跑完再等固定间隔）而非 `fixedRate`，避免任务执行慢导致的堆积。
> **注意**：`@Scheduled` 在多实例部署下每个实例都会执行，本项目对账任务通过**分布式锁 + 幂等条件更新**保证重复执行安全。

---

## 十二、数据库设计

### 12.1 `product` 商品表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 商品 ID |
| name | VARCHAR(128) | 商品名 |
| price | DECIMAL(10,2) | 价格 |
| stock | INT | 当前库存 |
| **version** | INT | **乐观锁版本号**，`UPDATE ... WHERE version = ?` |
| hot | TINYINT | 是否热点商品（1 = 缓存永不过期） |

### 12.2 `orders` 订单表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 订单 ID |
| order_no | VARCHAR(64) **UNIQUE** | 订单号，幂等最终兜底 |
| user_id / product_id | BIGINT | 归属 |
| status | TINYINT | 0 未支付 / 1 已支付 / 2 已取消 |
| amount | DECIMAL(10,2) | **下单时快照**的金额 |
| pay_time | DATETIME | 支付时间 |
| transaction_id | VARCHAR(64) **UNIQUE** | 支付流水号，支付幂等 |

**索引设计**

| 索引 | 字段 | 服务的查询 |
|------|------|-----------|
| `uk_order_no` | order_no | 订单号查询 + 下单幂等兜底 |
| `uk_transaction_id` | transaction_id | 支付幂等 |
| `idx_product_ct` | (product_id, create_time) | 按商品查订单 |
| `idx_user_product_status` | (user_id, product_id, status) | **秒杀防重兜底**：`WHERE user_id=? AND product_id=? AND status IN (0,1)` |
| `idx_status_ct` | (status, create_time) | **订单对账**：`WHERE status=0 AND create_time < ?` |

> 索引不是"想到哪建哪"：`idx_status_ct` 是专门为对账任务建的——否则 `OrderReconcileService` 每 60 秒全表扫一次 `orders`，数据量大时本身就是性能瓶颈。

### 12.3 `user` / `answer_record`

- `user`：`uk_username` 唯一索引；password 存 BCrypt 密文（VARCHAR(128)）
- `answer_record`：`uk_user_question (user_id, question_id)` 唯一索引，用于**消费幂等**（防止 `RPOPLPUSH` 重复消费写入两条）

### 12.4 初始化数据

| 表 | 数据 |
|----|------|
| product | id=1001，`Java并发编程实战课`，¥99.00，库存 100，hot=1（热点商品） |
| user | `admin`（管理员，用于黑名单管理接口） |

---

## 十三、压测基线

使用 JMeter（脚本见 `jmeter_test/Seckill压测.jmx`），四类场景：

| 场景 | 接口 | 并发数 | 预期 QPS | 预期 P99 | 校验指标 |
|------|------|--------|----------|----------|----------|
| 秒杀下单（核心） | `POST /api/seckill/{id}` | 1000 | ≥ 800 | < 200ms | **零超卖**（订单数 == 扣减库存） |
| 商品查询 | `GET /api/product/{id}` | 2000 | ≥ 3000 | < 50ms | 缓存命中率 > 95% |
| 限流验证 | `GET /api/test/hello` | 5000 | 返回 429 | - | 超窗口请求全部被限流 |
| 排行榜查询 | `GET /api/rank/top` | 1000 | ≥ 2000 | < 30ms | 返回正确 Top10 |

**⚠️ 压测前必读**

1. 压测会产生大量数据与违规记录，**不要对生产库执行**；建议单独用一个库。
2. 内置机制会干扰压测结果，需要提前知悉：
   - **限流**：秒杀接口 10000 次/秒、测试接口 5 次/10 秒，超限直接 429
   - **自动拉黑**：违规 5 次拉黑，压测时可能把自己压进黑名单，需手动移除
   - **用户防重**：同一用户同一商品只能抢一次，压测需准备**多用户数据集**
3. 压测前建议清理 Redis：`seckill:user:*`（防重）、`ratelimit:*`（限流）、`seckill:stock:*`（让库存重新预热）。
4. 压测期间同步观察 JVM GC（`jstat -gc <pid> 1000`）、Redis（`INFO stats`）与 MySQL 慢查询。

---

## 十四、设计亮点（面试向）

### 1. 库存零泄漏的三层安全网

```
第 1 层：RabbitMQ 延时消息（主路径）
          └─ 发送/消费失败 ↓ 不丢弃
第 2 层：Redis 重试队列 + 死信补偿队列（补偿）
          └─ 重试 5 次仍失败 ↓ 不丢弃
第 3 层：DB 全局对账（终极兜底）
          └─ 每 60s 扫「status=0 且超时」订单，逐笔兜底取消
```

**为什么需要第 3 层？** 只要依赖消息链路，就一定存在"消息永久丢失"的概率（重试耗尽、Redis 数据丢失、MQ 长时间不可用）。前两层都在**消息链路内部**兜底，只有第 3 层是**跳出链路、直接以 DB 为真相源**对账，因此它能覆盖所有"取消信号丢失"的路径。这也是分布式系统里"定期对账"这一模式的价值。

### 2. 补偿逻辑绑定"未提交"，而不是"发生异常"

库存扣减是"Redis 先扣、MySQL 后扣"的两段式操作，任何一段失败都要回补 Redis。这里有一条容易被忽略的原则：

> **补偿的触发条件应该是"我知道业务没成功"，而不是"我看见抛异常了"。**

如果按后者写，就可能回补一笔**其实已经提交成功**的订单库存，导致库存虚增（即少卖）。本项目用三个手段约束这件事：

- **编程式事务**（`TransactionTemplate`）把"提交"作为临界区内的最后一步；
- **`redisDeducted` + `committed` 双标志位**：只有「Redis 确实扣过」且「事务未提交」时才 `INCR` + 删防重 key；
- **afterCommit（发延时消息）已移出 `try` 块**：其异常独立处理（写 Redis 补偿队列），绝不触发库存回补。

全部分支（`InterruptedException` / `BusinessException` / `Exception`）统一走 `compensateRedis(...)`，补偿判定完全由两个标志位决定，彻底消除了"给已提交订单虚增库存"的可能。

### 3. 三级缓存语义统一

| 缓存 | 存储对象 | 是否含敏感/易变字段 |
|------|----------|---------------------|
| Caffeine `ProductDTO` | ProductDTO | ❌ 不含 stock / version |
| Redis `product:{id}` | ProductDTO | ❌ 不含 stock / version |
| Caffeine / Redis `UserDTO` | UserDTO | ❌ 不含 password |

库存是**高频变化**的数据，绝对不能和商品详情一起缓存（否则缓存与 DB 不一致的窗口无法收敛）；库存单独走 `seckill:stock:{id}` 计数器，并由 60s 对账纠偏。用户密码属于敏感数据，缓存与接口统一用 `UserDTO` 天然脱敏。

### 4. 锁必须在事务提交后释放

`@Transactional` + `finally { lock.unlock() }` 是一个经典陷阱：

```
线程A: 加锁 → 扣库存 → [事务尚未提交] → 释放锁
线程B:                        加锁 → 读到旧库存 → 超卖
```

改为 `TransactionTemplate` 编程式事务，把"提交"作为锁临界区内的最后一步，提交完成后再解锁。

### 5. 秒杀防重双保险

- Redis `SetIfAbsent`（TTL 1h）：挡住 99% 的重复请求，无 DB 压力
- DB 条件查询兜底（`status IN (0,1)`）：解决 Redis key 过期后的漏洞
- `uk_order_no` 唯一索引：即使前两层都失效，数据库也不会写入重复订单

**三层由快到慢、由弱到强**，这是幂等设计的标准思路：先挡流量，再保正确性。

### 6. 布隆过滤器的原子刷新

- `volatile` 引用 + **build-then-swap**：先在旁路构建完整新实例，再原子替换引用，读线程永远不会看到"半成品"过滤器
- 定时刷新（5min）兜底新增商品，`put()` 即时补位缩短不一致窗口
- 未就绪时**静默放行**——宁可放过几个请求让 DB 兜底，也不能误杀真实流量

### 7. 对账任务的索引反推设计

先确定"谁在扫这张表、用什么条件扫"，再决定建什么索引：

| 查询方 | WHERE 条件 | 对应索引 |
|--------|-----------|----------|
| 秒杀防重兜底 | `user_id=? AND product_id=? AND status IN (0,1)` | `idx_user_product_status` |
| 订单全局对账 | `status=0 AND create_time < ?` | `idx_status_ct` |

### 8. 其他工程细节

- **对账复用同一把锁**：库存对账与秒杀抢的是同一把 `seckill:lock:{productId}`，避免"对账把秒杀刚扣的库存又加回去"
- **锁内双检**：对账加锁后重新读一次实际库存，避免锁等待期间数据已变
- **拦截器分层与排除路径**：`/api/blacklist/**` 从**黑名单拦截器**排除（否则被拉黑后无法自救），但**必须经过登录拦截器**（防止匿名用户拉黑他人），敏感操作再加 `@RequireAdmin`
- **线程池统一**：所有异步任务走 `bizExecutor`（核心 4 / 最大 8 / 队列 1000 / `CallerRunsPolicy`），禁止 `new Thread`；拒绝策略选"调用方执行"保证任务不丢
- **限流用 Lua 脚本**：`ZREMRANGEBYSCORE + ZCARD + ZADD` 必须原子执行，否则并发下计数失真；用 `StringRedisTemplate` 避免 Jackson 序列化导致 Lua 拿到带引号的字符串

---

## 十五、常见问题 FAQ

**Q1：启动即报连接失败（MySQL / Redis / RabbitMQ）？**
本地方式请确认三个中间件都已启动，且 `application.yaml` 里的端口正确（注意 MySQL 若用 docker-compose 是 **3307**，不是 3306）。最省事的是直接用 `docker compose up -d --build`。

**Q2：秒杀一直返回「库存不足」，但数据库有库存？**
Redis 库存 key 未预热。调用 `POST /api/product/{id}/warmup` 预热，或重启应用（`StockWarmUpRunner` 会在启动时预热）。另外确认读写用的是**同一个 key 前缀**（`seckill:stock:`）——历史上踩过"写入前缀不一致导致全部售罄"的坑。

**Q3：第二次秒杀返回 1003 重复下单，怎么重新测？**
删除防重 key：`redis-cli del "seckill:user:{productId}:{userId}"`。若该用户已有订单，还需把订单状态改为 2（已取消）——**已取消订单允许重新秒杀**。

**Q4：请求返回 429？**
触发了滑动窗口限流。秒杀接口 10000 次/秒，测试接口 5 次/10 秒。清理 `ratelimit:*` 即可。

**Q5：请求返回 403「已被加入黑名单」？**
10 分钟内违规 5 次会被自动拉黑。用管理员账号调用 `DELETE /api/blacklist/remove?type=ip&key=<你的IP>` 解除，或直接 `redis-cli del blacklist:ip blacklist:count:ip:<IP>`。

**Q6：`docker compose down -v` 之后数据全没了？**
`-v` 会删除数据卷（MySQL / Redis / RabbitMQ 全部数据）。日常重启用 `docker compose down`（不带 `-v`）保留数据。

**Q7：admin 账号登录失败？**
`schema.sql` 中的初始密码哈希为示例值，若无法通过 `admin123` 登录，用 BCrypt 重新生成哈希后更新：

```sql
UPDATE user SET password = '<新生成的 BCrypt 哈希>' WHERE username = 'admin';
```

**Q8：这个项目可以用于生产吗？**
不建议。它是**教学/面试项目**：支付是模拟的、`application.yaml` 里保留了默认密码、`log-impl` 输出全部 SQL。若要做生产改造，至少需要：改掉默认密码、关闭 SQL stdout 日志、给 `orders` 表做分片或归档、把秒杀库存预热与对账做成可观测（埋点 + 告警）、把布隆过滤器换成 Redis 版（`RedisBloom`）以支持多实例强一致。

---

## 十六、配套学习资料

仓库内 `文档/` 目录提供四份配套资料（本地学习资料，已在 `.gitignore` 中排除，不随仓库发布）：

| 文档 | 内容 | 适合谁 |
|------|------|--------|
| `文档/LEARNING_GUIDE.md` | 新手完全学习手册，逐章讲原理 + 代码走读，含三轮学习法 | 第一次接触秒杀/Redis 的同学 |
| `文档/INTERVIEW_QUESTIONS.md` | **59 个开发踩坑与面试要点**（锁与事务顺序、幂等三层设计、序列化坑、库存对账锁内重查等） | 准备面试、想深挖细节 |
| `文档/Docker部署文档.md` | Docker 部署全流程 + 面试考法 | 想搞懂容器化部署 |
| `文档/PRESS_TEST.md` | JMeter 压测详细指南（四场景 + 监控命令） | 想亲自压一把 |
| `jmeter_test/` | 压测脚本 `Seckill压测.jmx` + 用户/会话数据集 | 直接开压 |

**推荐的阅读顺序**：`LEARNING_GUIDE`（建立全局认知）→ 跟着本文档的[快速开始](#六快速开始)跑一遍 → `INTERVIEW_QUESTIONS`（查漏补缺）→ 自己动手压测并观察监控指标。

---

## 免责声明

本项目仅用于**学习与技术交流**，未经过生产级安全加固，请勿直接用于生产环境。项目中的密码、账号均为本地演示用途，请勿复用。

如果这个项目对你有帮助，欢迎 Star ⭐
