# 基于 Redis 的高并发学习商城秒杀系统

> 一个可直接运行、功能完整的秒杀实战项目。覆盖缓存治理、分布式锁、限流、MQ 延时消息、定时对账等后端高频考点，代码量约 4500 行（68 个 Java 类）。

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
- [七、配置说明](#七配置说明)
- [八、接口列表](#八接口列表)
- [九、Redis Key 设计](#九redis-key-设计)
- [十、定时任务清单](#十定时任务清单)
- [十一、数据库设计](#十一数据库设计)

---

## 一、项目简介

秒杀系统的难点不在于把订单写进数据库，而在于**高并发下的三个"不能"**：

| 问题 | 后果 | 本项目对策 |
|------|------|-----------|
| 超卖 | 100 件商品卖出 120 单 | Redisson 分布式锁 + MySQL 乐观锁 version |
| 库存泄漏 | 用户下单不支付，库存被永久占用 | RabbitMQ 延时消息 + Redis 重试 + DB 定时对账 |
| 打垮 DB | 流量穿透到 MySQL | 布隆过滤器 + 三级缓存 + 滑动窗口限流 |

项目同时包含用户 / 商品 / 秒杀 / 订单 / 排行榜 / 答题 / 黑名单 / 管理员八个业务模块。

---

## 二、技术栈

| 分类 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言 | Java | 17 | Records、文本块 |
| 基础框架 | Spring Boot | 3.3.2 | 自动装配、内嵌 Tomcat |
| ORM | MyBatis-Plus | 3.5.7 | 单表 CRUD + 乐观锁插件 |
| 数据库 | MySQL | 8.0+ | 商品、订单、用户、答题记录 |
| 缓存 | Redis (Lettuce) | 6.0+ | 缓存、计数器、排行榜、限流、队列 |
| 本地缓存 | Caffeine | - | 一级缓存（进程内） |
| 分布式锁 | Redisson | 3.32.0 | 可重入锁 |
| 消息队列 | RabbitMQ | 3.10+ | 死信队列 + TTL 实现延时消息 |
| 布隆过滤器 | Guava | 33.2.0-jre | 防缓存穿透 |
| 分布式 Session | Spring Session Data Redis | - | 多实例共享登录态 |
| 密码加密 | BCrypt | - | 引入 Spring Security Crypto 单独使用 |
| 接口文档 | Knife4j (OpenAPI3) | 4.4.0 | 在线调试 `doc.html` |
| 容器化 | Docker / Docker Compose | - | 多阶段构建 + 一键编排 |

---

## 三、系统架构

```
                          ┌── 用户请求 ──┐
                          └──────┬───────┘
                                 │
                  ┌──────────────┼──────────────┐
                  ▼              ▼              ▼
            ┌──────────┐   ┌──────────┐   ┌──────────┐
            │  黑名单   │   │ 登录拦截  │   │ 限流切面  │
            │ order=0   │   │ order=1  │   │ ZSet 滑动 │
            └────┬─────┘   └────┬─────┘   └────┬─────┘
                 │               │               │
                 └───────────────┼───────────────┘
                                 ▼
                    ┌──────────────────────────────┐
                    │         SeckillService        │
                    │  ① 布隆过滤器（防穿透）        │
                    │  ② 三级缓存: Caffeine→Redis→DB│
                    │  ③ Redisson 分布式锁          │
                    │  ④ Redis DECR 预扣库存         │
                    │  ⑤ MySQL 乐观锁 version 兜底   │
                    │  ⑥ 编程式事务 + 防重          │
                    │  ⑦ 发送延时消息（30min TTL）   │
                    └──────────────────┬───────────┘
                                       │
          ┌────────────────────────────┼────────────────────────────┐
          ▼                            ▼                            ▼
    ┌──────────┐                ┌──────────┐                 ┌──────────┐
    │  MySQL   │                │  Redis   │                 │ RabbitMQ │
    │ 真相源    │                │ 缓存层    │                 │ 延时消息  │
    └────┬─────┘                └────┬─────┘                 └────┬─────┘
         │                            │                           │
         │           ┌─────── 每 60s 对账 ────────┐               │
         │           │                            │               │
         └───────────┴──── 第 3 层兜底 ◀──────────┘───────────────┘
                       StockReconcile / OrderReconcile
```

---

## 四、核心功能

### 4.1 Redis 缓存治理

- **三级缓存**：Caffeine 本地 → Redis 分布式 → MySQL，逐层回源
- **穿透防护**：Guava 布隆过滤器预热全量商品 ID，启动预热 + 5 分钟定时刷新 + 新增商品即时补位
- **击穿防护**：热点商品永不过期（`hot=1`）+ Redisson 互斥锁双重检查
- **雪崩防护**：TTL 随机抖动（30min ± 5min）
- **多实例一致性**：Redis Pub/Sub 广播失效通知，各实例收到后清除本地 Caffeine

### 4.2 并发控制

- **双层防超卖**：Redisson 分布式锁（`tryLock(3, 10, SECONDS)`）+ MySQL 乐观锁 version
- **用户防重**：Redis `SetIfAbsent` 快速过滤（TTL 1h）+ DB 状态查询兜底，双重保障防止重复下单；已取消订单允许重新秒杀
- **滑动窗口限流**：Redis ZSet + Lua 脚本保证"判断 + 计数"原子执行，秒杀接口 10000 次/秒

### 4.3 订单状态机

```
            下单成功
               │
               ▼
        ┌─────────────┐   支付（模拟）    ┌─────────────┐
        │ 0 未支付     │ ──────────────▶ │ 1 已支付     │
        └──────┬──────┘                  └─────────────┘
               │
               │ 超时 30min / 用户主动取消
               ▼
        ┌─────────────┐
        │ 2 已取消     │  事务内 WHERE status=0 条件更新，保证幂等
        └─────────────┘  取消只回滚 MySQL 库存，Redis 由对账校正
```

状态流转统一使用**条件更新**（`WHERE status=0`），并发下天然幂等。`uk_transaction_id` 唯一索引保证支付幂等。

### 4.4 库存与订单对账（三层兜底）

| 层级 | 组件 | 触发时机 | 说明 |
|------|------|----------|------|
| 第 1 层 | RabbitMQ 死信延时队列 | 下单 30 分钟后 | TTL 到期 → 死信 → 取消队列 |
| 第 2 层 | Redis 重试队列 + 死信补偿 | 发送/消费失败 | `DelayRetryService` 每 30s 重发，超 5 次转死信；`DeadLetterRetryService` 每 60s 重投 |
| 第 3 层 | 定时对账任务 | 每 60 秒 | `StockReconcileService` 以 MySQL 为准校正 Redis 库存（锁内重查 MySQL）；`OrderReconcileService` 扫 DB 超时订单兜底取消——无论 MQ/Redis 是否正常 |

### 4.5 消息可靠性

- 延时队列：`DirectExchange` + `x-message-ttl=1800000` + `x-dead-letter-exchange`
- 发送端：`publisher-confirm-type: correlated`，发送时携带 `CorrelationData` 同步等 Broker 确认（3s 超时），NACK/路由失败/超时统一抛异常流入 Redis 补偿队列
- 消费端：`acknowledge-mode: manual`，消费失败先落 Redis 补偿队列再 ACK
- 幂等：`WHERE status=0` 条件更新，已支付/已取消直接跳过

### 4.6 黑名单与安全

- **自动限流拉黑**：10 分钟窗口内违规 5 次自动加入黑名单
- **IP 可信代理边界**：`IpUtils` 先以 TCP 层 `remoteAddr` 判断是否属于配置的可信代理（CIDR 支持），不可信直连一律不采信 XFF/X-Real-IP
- **三级拦截器**：黑名单（order=0）→ 登录（order=1）→ 管理员（order=2，`/api/admin/**` + `/api/blacklist/**`）
- **密码加密**：BCrypt；登录态存 Redis Session；login 时显式清除非 admin 账号的 `isAdmin` 标志位
- **数据脱敏**：所有接口返回 UserDTO / ProductDTO，不暴露 password、stock/version
- **敏感配置抽离**：密码等通过环境变量或 `.env` 文件注入，`application.yaml` 不硬编码明文默认值

### 4.7 答题异步与排行榜

- **异步批量落库**：Redis List 收集答题记录，每 5 秒定时批量写入 DB
- **首答判重**：入队前 `SADD answered:{userId} {questionId}`，已答过直接返回 3001；首答判定同时覆盖记录与积分两个副作用
- **幂等写入**：`uk_user_question` 唯一索引 + `ON DUPLICATE KEY UPDATE`
- **可靠队列**：`RPOPLPUSH` 原子转移至处理队列，落库成功后才删除
- **积分排行榜**：正确答题得 10 分，写入 Redis ZSet

---

## 五、项目结构

```
seckill-mall
├── src/main/java/com/sygzcd/seckillmall
│   ├── SeckillMallApplication.java      # 启动类
│   ├── annotation/RequireAdmin.java     # 管理员权限注解
│   ├── aop/                              # RateLimitAspect + LogAspect
│   ├── common/                           # Result / ResultCode / BusinessException
│   │   ├── ResultCode.java              # 业务码（200/4xx/5xx + 10xx/20xx/30xx）
│   │   ├── GlobalExceptionHandler.java  # 全局异常处理
│   │   ├── ProductDTO.java / UserDTO.java / PayResultDTO.java
│   │   └── util/IpUtils.java            # IP 可信代理边界
│   ├── config/                           # 11 个配置类
│   ├── controller/                       # 9 个 Controller，22 个接口
│   │   └── AdminProductController.java   # 管理员商品属性更新
│   ├── entity/                           # Product / Orders / User / AnswerRecord
│   ├── interceptor/                      # BlackList / Auth / Admin
│   ├── mapper/                           # 4 个 Mapper 接口 + XML
│   └── service/
│       ├── impl/                         # 10 个业务实现
│       │   ├── SeckillServiceImpl.java      # 秒杀核心
│       │   ├── OrderServiceImpl.java        # 订单（不再 INCR Redis 库存）
│       │   ├── StockReconcileService.java   # 库存对账（锁内重查 MySQL）
│       │   ├── AnswerAsyncServiceImpl.java  # 答题异步落库 + 首答判重
│       │   └── ...
│       └── mq/                           # OrderDelayProducer / OrderCancelConsumer / 重试服务
├── src/main/resources
│   ├── application.yaml                 # 本地配置（端口 3307，密码从环境变量读）
│   ├── application-docker.yaml          # Docker 配置（服务名寻址）
│   └── mapper/*.xml                     # 4 个 XML
├── sql/schema.sql                       # 建库建表 + 索引 + 初始数据
├── jmeter_test/                         # JMeter 压测脚本
├── Dockerfile                           # 多阶段构建，非 root 运行
├── docker-compose.yml                   # MySQL + Redis + RabbitMQ + App
├── .env.example                         # 环境变量占位模板
└── README.md
```

---

## 六、快速开始

### 6.1 方式一：本地运行

**1) 环境准备**

| 依赖 | 版本要求 | 默认端口 |
|------|----------|----------|
| JDK | 17+ | - |
| Maven | 3.8+（或用自带 `./mvnw`） | - |
| MySQL | 8.0+ | 3306（compose 映射 3307） |
| Redis | 6.0+ | 6379 |
| RabbitMQ | 3.10+ | 5672 / 控制台 15672 |

> RabbitMQ 必须使用 `admin/admin123` 账号。

**2) 初始化数据库**

```bash
mysql -u root -p < sql/schema.sql
```

脚本创建 `seckill_mall` 库、4 张表、8 个索引，插入 1 个秒杀商品和 1 个管理员账号。

**3) 配置环境变量**

新建 `.env`（已被 gitignore 覆盖）：

```bash
MYSQL_ROOT_PASSWORD=你的密码
RABBITMQ_PASSWORD=admin123
```

**4) 启动**

```bash
./mvnw spring-boot:run
# 或
mvn clean package -DskipTests && java -jar target/seckill-mall-0.0.1-SNAPSHOT.jar
```

**5) 访问**

- 接口文档（Knife4j）：<http://localhost:8080/doc.html>
- 健康检查：<http://localhost:8080/actuator/health>

### 6.2 方式二：Docker 一键部署（推荐）

无需在本机安装任何中间件。

```bash
# 1. 准备环境变量（首次执行）
cat > .env <<'EOF'
MYSQL_ROOT_PASSWORD=change_me
RABBITMQ_PASSWORD=admin123
EOF

# 2. 构建并启动
docker compose up -d --build

# 3. 查看状态（等待 4 个服务 healthy）
docker compose ps

# 4. 跟踪日志
docker compose logs -f app
```

启动顺序由 `depends_on: condition: service_healthy` 保证。

| 服务 | 容器名 | 本机端口 | 说明 |
|------|--------|----------|------|
| MySQL | seckill-mysql | 3307 → 3306 | 首次启动自动执行 `sql/schema.sql` |
| Redis | seckill-redis | 6379 | `maxmemory 256mb`，`allkeys-lru` |
| RabbitMQ | seckill-rabbitmq | 5672 / 15672 | 控制台 <http://localhost:15672> |
| App | seckill-app | 8080 | 非 root 用户运行 |

**常用运维命令**

```bash
docker compose logs -f app             # 应用日志
docker compose restart app             # 重启应用
docker compose down                    # 停止并删除容器（保留数据卷）
docker compose down -v                 # ⚠️ 连同数据卷一起删除
```

### 6.3 冒烟测试（复制即用）

```bash
BASE=http://localhost:8080

# 1. 注册
curl -s -X POST "$BASE/api/user/register?username=tom&password=123456"

# 2. 登录（-c 保存 Cookie）
curl -s -c cookie.txt -X POST "$BASE/api/user/login?username=tom&password=123456"

# 3. 查询商品
curl -s -b cookie.txt "$BASE/api/product/1001"

# 4. ★ 秒杀下单
curl -s -b cookie.txt -X POST "$BASE/api/seckill/1001"

# 5. 再抢一次 → 应返回「重复下单」(code 1003)
curl -s -b cookie.txt -X POST "$BASE/api/seckill/1001"

# 6. 我的订单
curl -s -b cookie.txt "$BASE/api/order/my?page=1&size=10"

# 7. 答题 + 查看排行榜
curl -s -b cookie.txt -X POST "$BASE/api/answer/submit?questionId=1&correct=true"
curl -s -b cookie.txt "$BASE/api/rank/top?n=10"
```

---

## 七、配置说明

### 7.1 环境变量

| 变量名 | 说明 | 默认/示例值 |
|--------|------|-------------|
| `MYSQL_USERNAME` | MySQL 用户名 | `root` |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `change_me` |
| `MYSQL_DATABASE` | MySQL 数据库名 | `seckill_mall` |
| `RABBITMQ_USERNAME` | RabbitMQ 用户名 | `admin` |
| `RABBITMQ_PASSWORD` | RabbitMQ 密码 | `admin123` |
| `SPRING_PROFILES_ACTIVE` | 激活配置 | `docker`（compose 里设） |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx512m` |

本地 profile 中密码不带默认值，未设置则启动失败（**fail-closed**）。

### 7.2 两套 Profile 的差异

| 配置项 | `application.yaml`（本地） | `application-docker.yaml` |
|--------|---------------------------|---------------------------|
| MySQL 地址 | `localhost:3307` | `mysql:3306`（容器服务名） |
| Redis 地址 | `localhost:6379` | `redis:6379` |
| RabbitMQ 地址 | `localhost:5672` | `rabbitmq:5672` |
| 可信代理 | `127.0.0.1,::1`（本机） | 空（按需配置容器网桥网段） |

> 容器间通过 Docker 网络的服务名互相寻址，因此 Docker profile 中不能写 `localhost`。`trusted-proxies` 默认留空，需按部署时 Nginx 所在网段手动配置（如 `172.16.0.0/12`）。

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
| 200 | 成功 | 429 | 请求过于频繁（真实 HTTP 429） |
| 400 | 参数错误 | 1001 | 秒杀失败 |
| 401 | 未登录（真实 HTTP 401） | 1002 | 商品已售罄 |
| 403 | 无权限 / 已被拉黑（真实 HTTP 403） | 1003 | 重复下单 |
| 404 | 资源不存在 | 2001 / 2002 | 用户名已存在 / 密码错误 |
| 500 | 服务器内部错误 | 3001 | 该题已作答 |

> 业务异常走 body code（HTTP 统一 200），拦截器返回的认证/授权/限流异常走真实 HTTP 状态码。

### 8.3 接口清单

登录通过 Session + Cookie；管理员接口需要 admin 账号。

| 模块 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 用户 | POST | `/api/user/register` | 公开 | 注册（BCrypt） |
| 用户 | POST | `/api/user/login` | 公开 | 登录 |
| 用户 | GET | `/api/user/current` | 登录 | 获取当前用户 |
| 用户 | POST | `/api/user/logout` | 登录 | 退出 |
| 商品 | GET | `/api/product/{id}` | 登录 | 商品详情 |
| 商品 | GET | `/api/product/{id}/stock` | 登录 | 实时库存 |
| 商品 | POST | `/api/product/{id}/warmup` | 登录 | 手动预热 |
| 秒杀 | POST | `/api/seckill/{productId}` | 登录 | 秒杀下单（限流 10000/s） |
| 订单 | GET | `/api/order/{orderNo}` | 登录 | 订单详情 |
| 订单 | POST | `/api/order/{orderNo}/cancel` | 登录 | 取消未支付订单 |
| 订单 | POST | `/api/order/{orderNo}/pay` | 登录 | 模拟支付 |
| 订单 | GET | `/api/order/my` | 登录 | 我的订单（分页） |
| 排行榜 | GET | `/api/rank/top` | 登录 | Top N（默认 10） |
| 排行榜 | GET | `/api/rank/my` | 登录 | 我的排名 |
| 答题 | POST | `/api/answer/submit` | 登录 | 提交答题（异步落库） |
| 商品管理 | POST | `/api/admin/product/{id}/update` | **管理员** | 修改商品属性（name ≤128 / price 0.01~99999999.99 / hot ∈ {0,1}） |
| 黑名单 | GET | `/api/blacklist/check` | 登录 | 检查是否在黑名单 |
| 黑名单 | GET | `/api/blacklist/list` | 登录 | 查看黑名单 |
| 黑名单 | POST | `/api/blacklist/add` | **管理员** | 加入黑名单 |
| 黑名单 | DELETE | `/api/blacklist/remove` | **管理员** | 移出黑名单 |
| 测试 | GET | `/api/test/hello` | 公开 | 限流 + 自动拉黑演示 |

---

## 九、Redis Key 设计

### 9.1 商品与库存

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `product:{id}` | String | 30min ± 5min | 商品详情（不含 stock/version），热点商品永不过期 |
| `seckill:stock:{productId}` | String | - | 实时库存计数器 |
| `lock:product:{id}` | - | - | 商品缓存重建互斥锁 |

### 9.2 秒杀与订单

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `seckill:lock:{productId}` | Redisson Lock | 10s 看门狗 | 秒杀 + 对账共用 |
| `seckill:user:{productId}:{userId}` | String | 1h | 抢购防重标记 |
| `seckill:delay:retry` | List | - | 延时消息发送失败重试（每 30s 消费） |
| `seckill:delay:dead` | List | - | 重试耗尽死信（每 5min 兜底取消） |
| `seckill:dead:retry` | List | - | 死信补偿队列（每 60s 重投） |

### 9.3 用户与会话

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `user:{id}` | String | 30min ± 5min | 用户缓存（不含 password） |
| `login:session:{userId}` | String | Session 有效期 | 登录态 |

### 9.4 排行榜、答题与安全

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `rank:score` | ZSet | - | 积分排行榜 |
| `answer:queue` / `answer:queue:processing` | List | - | 答题主队列 + 处理中队列 |
| `answered:{userId}` | Set | - | 首答判重 |
| `ratelimit:seckill:{ip}` | ZSet | 1s | 秒杀限流 |
| `blacklist:ip` / `blacklist:user` | Set | - | 黑名单 |
| `blacklist:count:*` | String | 10min | 违规计数 |

---

## 十、定时任务清单

| 任务 | 周期 | 作用 |
|------|------|------|
| 布隆过滤器全量刷新 | 5 min | 从 DB 重建并原子替换实例 |
| 库存对账 | 60 s | 锁内重查 MySQL 校正 Redis 库存 |
| 订单全局对账 | 60 s | 扫超时未支付订单兜底取消 |
| 答题异步落库 | 5 s | Redis List → DB 批量写入 |
| 延时消息重试 | 30 s | 重发失败延时消息，超 5 次转死信 |
| 死信补偿重投 | 60 s | 重投 `seckill:dead:retry` |

> 所有任务统一 `fixedDelay`（上一轮跑完再等间隔），避免堆积。多实例下每个实例都执行，通过分布式锁 + 幂等条件更新保证安全。

---

## 十一、数据库设计

### 11.1 `product` 商品表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 商品 ID |
| name | VARCHAR(128) | 商品名 |
| price | DECIMAL(10,2) | 价格 |
| stock | INT | 当前库存 |
| version | INT | 乐观锁版本号 |
| hot | TINYINT | 是否热点（1 = 缓存永不过期） |

### 11.2 `orders` 订单表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 订单 ID |
| order_no | VARCHAR(64) **UNIQUE** | 订单号，幂等兜底 |
| user_id / product_id | BIGINT | 归属 |
| status | TINYINT | 0 未支付 / 1 已支付 / 2 已取消 |
| amount | DECIMAL(10,2) | 下单时快照金额 |
| pay_time | DATETIME | 支付时间 |
| transaction_id | VARCHAR(64) **UNIQUE** | 支付流水号 |

**索引**

| 索引 | 字段 | 服务 |
|------|------|------|
| `uk_order_no` | order_no | 下单幂等 |
| `uk_transaction_id` | transaction_id | 支付幂等 |
| `idx_user_product_status` | (user_id, product_id, status) | 秒杀防重兜底 |
| `idx_status_ct` | (status, create_time) | 订单对账 |

### 11.3 其他表

- `user`：`uk_username` 唯一索引；password 存 BCrypt 密文
- `answer_record`：`uk_user_question (user_id, question_id)` 唯一索引，消费幂等

### 11.4 初始化数据

product id=1001，`Java并发编程实战课`，¥99.00，库存 100，hot=1；admin 管理员账号。
