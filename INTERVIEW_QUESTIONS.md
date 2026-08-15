# 开发踩坑与面试要点

## 1. 为什么只引入 spring-security-crypto 而不是 spring-boot-starter-security？

项目需要 BCrypt 加密密码，我一开始直接加了 `spring-boot-starter-security`，结果启动后**所有接口都被拦截**，返回 401，包括 Knife4j 文档页面也打不开了。

排查后发现 Spring Security 自动配置了 `SecurityFilterChain`，默认拦截所有请求。虽然可以通过配置放行，但会引入大量不需要的功能（CSRF、表单登录、Session 管理策略等），和项目已有的 `AuthInterceptor` 登录拦截器也会冲突。

**最终方案**：只引入 `spring-security-crypto`，单独使用 `BCryptPasswordEncoder`，不触发 Spring Security 的自动配置。这样既拿到了 BCrypt 能力，又不影响项目已有的鉴权体系。

**面试怎么说**：选依赖的时候要看它带了什么自动配置，不能只看功能。`starter` 会触发一整套默认行为，如果只需要其中一个工具类，引具体模块比引 starter 更安全。

---

## 2. MyBatis-Plus 的 Wrapper 家族：查询和更新不能混用

取消订单回滚库存时，我写了这样的代码：
```java
productMapper.update(null, 
    new LambdaQueryWrapper<Product>()
        .eq(Product::getId, productId)
        .setSql("stock = stock + 1")  // 编译报错
);
```

编译报错：`LambdaQueryWrapper` 没有 `setSql` 方法。

看了源码才发现 MyBatis-Plus 的 Wrapper 是两套体系：
- `QueryWrapper` / `LambdaQueryWrapper` → 只生成 WHERE 条件，用于 SELECT
- `UpdateWrapper` / `LambdaUpdateWrapper` → 既能生成 WHERE 条件，也能 SET 字段，用于 UPDATE

`setSql` 是 `UpdateWrapper` 独有的方法，因为查询不需要自定义 SET 子句。

**最终方案**：改为 `UpdateWrapper`，但代价是 WHERE 条件不能用 Lambda 方法引用（`eq("id", productId)` 而不是 `eq(Product::getId, productId)`），因为 `UpdateWrapper` 不是泛型 Lambda 体系。

**面试怎么说**：MyBatis-Plus 的 Wrapper 设计遵循了接口隔离原则，查询和更新各自有独立的抽象。`LambdaQueryWrapper` 用方法引用避免硬编码字段名，但 `UpdateWrapper` 需要额外的 SET 能力，所以没有做成同一个类。

---

## 3. SQL 管理方式：注解 vs XML 的统一决策

项目初期 `ProductMapper.decreaseStockWithVersion` 用了 `@Update` 注解直接写 SQL：
```java
@Update("UPDATE product SET stock = stock - 1, version = version + 1 " +
        "WHERE id = #{id} AND stock > 0 AND version = #{version}")
int decreaseStockWithVersion(@Param("id") Long id, @Param("version") Integer version);
```

但其他 Mapper 的复杂 SQL 都写在 XML 里。同一个项目两种风格，维护时容易遗漏——比如改表字段名，注解里的 SQL 不会像 XML 那样集中管理，容易漏改。

**最终方案**：删除 `@Update` 注解，SQL 统一写在 `ProductMapper.xml` 中。

**面试怎么说**：注解适合单行简单 SQL（如 `@Select("SELECT * FROM user WHERE id = #{id}")`），但涉及多行、动态条件、复杂 JOIN 的 SQL 还是 XML 更清晰。项目规范要尽早定，中途统一风格虽然改动不大，但如果项目已经上线再改成本就高了。

---

## 4. 乐观锁回滚库存：为什么用 setSql("stock = stock + 1") 而不是先查再更新？

取消订单时需要回滚库存，第一反应是：先查出当前库存 → +1 → 更新回去。但这有并发问题：两个线程同时读到 stock=50，都更新为 51，实际只加了 1 次。

用 `setSql("stock = stock + 1")` 让数据库自己算，SQL 变成：
```sql
UPDATE product SET stock = stock + 1 WHERE id = ?
```
数据库行锁保证原子性，不需要应用层加锁。

**面试怎么说**：库存这种高并发场景，能用数据库原子操作就别在应用层做"读-改-写"。`stock = stock + 1` 是数据库层面的原子操作，而行级锁天然保证并发安全。同理，扣库存用 `stock = stock - 1` 配合 `stock > 0` 条件，比先查再扣更安全。

---

## 5. Session 安全：从存 User 对象到只存 userId + UserDTO 脱敏

**问题起源**：早期设计是登录成功后将完整 User 对象（含密码 Hash）存入 Session：
```java
session.setAttribute("user", user);
```

存在两个安全隐患：
1. **敏感信息泄露**：Session 中存储了 BCrypt 加密后的密码 Hash，一旦 Session 数据被意外暴露（如 Redis 被误操作、日志打印等），攻击者可以获取密码 Hash 进行离线破解
2. **序列化问题**：Spring Session 将 Session 存到 Redis 时用 JDK 序列化，要求实体必须实现 `Serializable`，且序列化后的二进制数据不可读，调试困难

**最终方案**：Session 只存 `userId`，用户信息通过 `UserDTO`（脱敏 DTO）在需要时从缓存/数据库查询：
```java
// 登录时：只存 userId，不存 User 对象
session.setAttribute("userId", user.getId());

// 查询时：通过 userId 从缓存获取脱敏的 UserDTO
UserDTO userDTO = userCacheService.getById(userId);
```

UserDTO 只包含 `id`、`username`、`createTime`，不包含 `password` 等敏感字段。

**配合三级缓存**：`UserCacheService` 实现 Caffeine → Redis → MySQL 三级缓存，Redis 存 JSON 格式的 UserDTO，可直接阅读调试。

**面试怎么说**：Session 安全设计的核心原则是"最小化存储"——Session 中只存必要的标识信息（userId），不存完整对象。用户信息通过 DTO 按需查询，既避免了敏感信息泄露，又通过缓存保证了查询性能。这个设计同时解决了序列化问题（Session 只存 Long 类型，不需要 Serializable）和数据一致性问题（每次查询走缓存/数据库，始终是最新数据）。

---

## 6. Redis 预扣库存成功但 MySQL 事务失败，库存"丢了"

秒杀下单流程是：Redis 先 `DECR` 预扣库存 → MySQL 乐观锁扣库存。如果 MySQL 事务失败（比如乐观锁冲突、数据库连接超时），Redis 的库存已经扣了但 DB 没扣，库存就"丢了"——用户看到库存不足，但实际商品没卖出去。

**解决**：在 `@Transactional` 的 catch 块中执行 `INCR` 回补 Redis 库存。如果回补也失败（Redis 也挂了），记录补偿日志，后续通过定时任务或人工介入恢复一致性。

**面试怎么说**：Redis 和 MySQL 是两个独立的数据源，没有分布式事务保障。我的策略是"先扣 Redis，失败直接返回；MySQL 失败则回补 Redis"，本质是用补偿机制代替强一致性。面试中如果被追问"回补也失败怎么办"，答案是记录补偿日志 + 定时任务重试，或者用消息队列做最终一致性保障。

---

## 7. Session 存什么？只存 userId 的设计取舍

登录成功后 Session 存什么，曾经有三种方案：
- **存整个 User 对象**：方便但有敏感信息泄露风险，且数据变更后 Session 里是旧值
- **存 userId + username**：兼顾标识和展示，但仍然是冗余的
- **只存 userId**：每次需要用户信息时查缓存，但数据始终最新

**最终选择只存 userId**：
```java
session.setAttribute("userId", user.getId());
// 不再存 user 对象或 username
```

**为什么只存 userId 更好？**
1. **安全性**：Session 中没有任何敏感信息，即使 Session 数据泄露也不会暴露密码 Hash
2. **数据一致性**：用户信息修改后（如改用户名），不需要清理 Session，下次查询自动获取最新值
3. **性能**：配合 `UserCacheService` 的三级缓存，查询用户信息走 Caffeine（本地缓存），几乎零开销
4. **简洁性**：拦截器和业务层统一通过 `userId` 获取用户信息，逻辑简单

**拦截器怎么判断登录态？**
```java
Long userId = (Long) session.getAttribute("userId");
if (userId == null) {
    // 未登录
    return false;
}
// 已登录，userId 就是凭证
```

**面试怎么说**：Session 设计遵循"最小权限原则"——Session 中只存认证必需的最小信息（userId），其他业务数据按需获取。如果担心查询性能，用缓存解决（项目用了三级缓存），而不是把数据冗余存到 Session 里。这种设计的好处是安全性高、数据一致性好、逻辑简单，代价是多一次缓存查询（但本地缓存命中时几乎无延迟）。

---

## 8. UserMapper 遗留 @Select 注解：规范执行不彻底

之前删 `ProductMapper` 的 `@Update` 注解时统一了 SQL 到 XML，但 `UserMapper` 的 `@Select` 漏了。直到阶段7排查时才发现——同一个项目里，有的 SQL 在注解里，有的在 XML 里，维护时容易遗漏。

**更深的问题**：MyBatis-Plus 对同一个方法，如果**同时**有注解 SQL 和 XML SQL，启动时会报 ERROR 日志（虽然不崩溃，但日志里会有冲突警告）。虽然这里不是同时存在，但混用本身就是隐患——改表名时要全局搜索注解和 XML 两个地方。

**解决**：删除 `@Select`，SQL 移到 `UserMapper.xml`。

**面试怎么说**：SQL 管理方式要在项目初期就定好统一规范。我项目里所有 SQL 统一走 XML，理由是：① 动态 SQL（if/foreach）XML 更灵活；② 复杂 JOIN 在注解里可读性差；③ 集中管理方便排查慢查询。注解只适合极简的单行 SQL，但项目一旦复杂就不该混用。

---

## 9. 布隆过滤器防穿透：为什么不用缓存空值？

缓存穿透的两种主流方案：① 缓存空值（查询不到也缓存 `null`，设短 TTL）；② 布隆过滤器（预存合法 ID，请求先过布隆过滤器校验）。

我选了布隆过滤器，原因有三：
- **空间效率**：100 万商品 ID，缓存空值要 100 万个 Redis Key，布隆过滤器只需约 1.2MB（0.01% 误判率下）
- **攻击场景**：如果黑客用随机不存在的 ID 暴力请求，缓存空值会导致 Redis 被写满；布隆过滤器只读不写，天然免疫
- **启动预热**：通过 `CommandLineRunner` 在项目启动时自动查询全量商品 ID 调用 `init()`，确保布隆过滤器在接收请求前已就绪

**误判率的取舍**：布隆过滤器有误判（说存在可能不存在，但说不存在一定不存在），1% 的误判率意味着 1% 的非法请求会穿透到 DB。可以通过增大位数组降低误判率，但空间成本增加。我用的 0.01%，在空间和准确率之间取了平衡。

**面试怎么说**：缓存空值适合数据量小、穿透 Key 相对固定的场景；布隆过滤器适合数据量大、需要防御恶意攻击的场景。两者不互斥，可以组合使用——布隆过滤器挡大部分，缓存空值兜底误判的少量请求。

---

## 10. 三级缓存多实例一致性：为什么用 Redis Pub/Sub 而不是 MQ？

项目用 Caffeine（本地缓存）+ Redis（分布式缓存）+ MySQL 做三级缓存。多实例部署时，每个实例有自己的 Caffeine，一个实例更新数据后，其他实例的 Caffeine 还是旧值，导致脏读。

**方案对比**：
- **Redis Pub/Sub**：发布者往频道发消息，所有订阅者实时收到并清除本地缓存。延迟低（毫秒级），但无持久化——订阅者断线期间的消息会丢失
- **RabbitMQ**：可靠投递、有 ACK 机制，但引入 MQ 做缓存失效通知太重了，而且 MQ 的路由模型（Queue/Exchange）和"广播通知所有实例"的场景不完全匹配（需要 Fanout Exchange）
- **定时过期**：给 Caffeine 设短 TTL（如 5 秒），最多 5 秒不一致。实现最简单，但一致性窗口太大

**最终选 Pub/Sub**：缓存失效通知是"尽力而为"的场景——即使丢了消息，下次请求也会从 Redis 拿最新值回填 Caffeine，最终一致。不需要 MQ 的可靠性保证，而且 Pub/Sub 和 Redis 复用同一连接，零额外依赖。

**面试怎么说**：选 Pub/Sub 不是因为它"更好"，而是因为它的特性和场景匹配——低延迟、广播模型、容忍消息丢失。如果场景换成"订单状态变更通知"，那就必须用 MQ，因为那种场景丢消息等于丢订单。技术选型的核心是场景匹配，不是技术优劣。

---

## 11. RedisTemplate Jackson 序列化导致 Lua 脚本限流失效

限流切面用 `RedisTemplate.execute()` 执行 Lua 脚本做滑动窗口限流，参数直接传 `int` 和 `long`：

```java
redisTemplate.execute(script, keys, rateLimit.windowSec(), rateLimit.maxCount(), now);
```

压测时发现限流完全不生效——10 秒内请求 12 次全部通过。查 Redis 发现限流 ZSet 根本没写入。

**根因**：`RedisTemplate` 配了 `Jackson2JsonRedisSerializer` 作为 value 序列化器，执行 Lua 脚本时，`int` 参数被 Jackson 序列化成带类型信息的 JSON 字节。Lua 脚本中 `tonumber(ARGV[1])` 收到的不是 `"5"` 而是一段 JSON，`tonumber` 返回 `nil`，导致条件判断全部失效。

**解决**：改用 `StringRedisTemplate`，所有参数用 `String.valueOf()` 转字符串。`StringRedisTemplate` 的序列化器是 `StringRedisSerializer`，直接把字符串转字节，不加任何 JSON 包装，Lua 脚本收到的就是纯净的 `"5"`，`tonumber` 正常解析。

**面试怎么说**：`RedisTemplate` 的序列化器不仅影响存取值，还影响 Lua 脚本的参数传递。用 `Jackson2JsonRedisSerializer` 时，非 String 类型的参数会被序列化成 JSON，Lua 脚本的 `tonumber` 无法解析。这是一个很容易踩的坑——开发时不会报错，但脚本逻辑静默失效。执行 Lua 脚本统一用 `StringRedisTemplate` 是最安全的做法。

---

## 12. 库存 Key 前缀不一致导致秒杀全部售罄

压测时 1000 个用户秒杀，全部返回"商品已售罄"，但 Redis 中库存是 100。

排查发现：`SeckillServiceImpl` 的预扣库存用 `seckill:stock:{id}`，但 `ProductServiceImpl.getStock()` 用的是 `product:stock:{id}`。两个不同的 Key，导致秒杀时 `DECR` 操作的 Key 和库存检查的 Key 不是同一个——库存检查读到的是空值（nil），`DECR` 对不存在的 Key 操作返回 -1，直接判定售罄。

**根因**：缓存 Key 的命名规范没有统一管理。秒杀模块和商品模块各自定义了库存 Key 前缀，没有抽成常量或公共规范。

**解决**：统一用 `seckill:stock:` 前缀，并在 `ProductServiceImpl` 中修改常量定义。同时新增 `StockWarmUpRunner` 在启动时自动预热热点商品库存到 `seckill:stock:` Key，避免手动 SET。

**面试怎么说**：Redis Key 命名看似是小事，但在多人协作或多模块项目中，Key 命名不一致会导致隐蔽的数据隔离 Bug。最佳实践是把 Key 前缀统一定义为常量，比如 `RedisKeyConst.STOCK_PREFIX = "seckill:stock:"`，所有地方引用常量而不是硬编码字符串。

---

## 13. 限流为什么要用 Lua 脚本？本地 Semaphore 的作用？

限流切面做了两层：
1. **本地 Semaphore**（`new Semaphore(100)`）：单机并发控制，超过 100 个请求同时进入业务逻辑时直接拒绝
2. **Redis ZSet 滑动窗口**：分布式限流，10 秒内同一 IP 最多 5 次请求

**为什么用 Lua 脚本**：滑动窗口限流需要三步原子操作——① `ZREMRANGEBYSCORE` 清理过期记录、② `ZCARD` 统计当前窗口请求数、③ `ZADD` 写入新请求。如果分三条命令执行，高并发下会有竞态条件：线程 A 执行完 `ZCARD` 发现是 4 次（还没到 5），线程 B 也执行 `ZCARD` 发现也是 4 次，两个都 `ZADD`，实际变成了 6 次。Lua 脚本保证三步在 Redis 中原子执行，不会被其他命令打断。

**为什么要两层限流**：Redis 限流是分布式的，但每个请求都要走一次 Redis 往返，如果突发流量太大，Redis 本身可能被打挂。本地 Semaphore 先在 JVM 层面挡住超量并发，只有通过本地限流的请求才会去访问 Redis，保护 Redis 不被过载。

**面试怎么说**：限流设计要分层。本地限流（Semaphore/令牌桶）保护应用自身不被打爆，分布式限流（Redis Lua）保护下游服务。Lua 脚本解决的是"检查+写入"的原子性问题，如果不要求原子性，可以用简单的 `INCR + EXPIRE` 方案，但滑动窗口精度更高。

---

## 14. 订单超时取消：死信队列 vs 延时插件 vs 定时任务

订单创建后 30 分钟未支付需要自动取消并回补库存。三种方案对比：

| 方案 | 优点 | 缺点 |
|------|------|------|
| **定时任务轮询** | 实现简单 | 有延迟（轮询间隔就是最大延迟）、全表扫描 DB 压力大 |
| **RabbitMQ 死信队列** | 不依赖额外插件、消息可靠 | 需要一个正常队列 + 死信队列的配合，配置稍复杂 |
| **RabbitMQ 延时插件** | 配置简单、精度高 | 需要安装 `rabbitmq_delayed_message_exchange` 插件，运维成本 |

**选了死信队列**：项目已有 RabbitMQ，不引入新依赖。死信队列方案虽然配置复杂，但不需要额外安装插件，且消息可靠性有保障（ACK 机制 + 持久化）。订单消息进入正常队列时设 TTL=30 分钟，过期后自动进入死信队列，死信队列的消费者执行取消逻辑。

**面试怎么说**：如果项目已有 RabbitMQ，死信队列是订单超时的首选——不需要额外组件，消息可靠性有保障。如果对精度要求极高（比如秒级超时），用延时插件更合适。定时任务适合超时时间不敏感、数据量小的场景，比如"每天凌晨清理过期数据"。我选死信队列还有一个原因：死信队列的消息可以被重新投递，如果取消操作失败，消息不会丢，可以重试。

---

## 15. 分布式锁释放早于事务提交：超卖窗口

秒杀方法用 `@Transactional` 管理事务，同时方法内部用 Redisson 分布式锁串行化：

```java
@Transactional(rollbackFor = Exception.class)
public Orders seckill(...) {
    RLock lock = redissonClient.getLock(lockKey);
    try {
        lock.lock();
        // Redis 预扣库存、MySQL 乐观锁扣库存、建单
    } finally {
        lock.unlock();   // 锁在这里释放
    }
}
```

**问题**：`@Transactional` 由 Spring AOP 代理管理，事务提交发生在**方法返回之后**。而 `finally` 块中的 `lock.unlock()` 在方法返回**之前**执行。执行时序是：

```
线程A: 业务执行完 → lock.unlock()（锁已释放）→ 方法返回 → AOP 提交事务
线程B: 获取到锁 → selectById 读到旧 version → 乐观锁可能误判成功
```

在 READ_COMMITTED 隔离级别下，线程 B 的 `selectById` 读不到线程 A 未提交的数据，如果 B 读到的是旧 version，`WHERE version=旧值` 可能更新成功——虽然 MySQL 行锁在多数情况下会兜底（B 的 UPDATE 会等 A 的 X 锁释放），但锁的语义已经混乱，且存在理论上的超卖窗口。

**修复**：去掉方法级 `@Transactional`，改用 `TransactionTemplate` 编程式事务，把 MySQL 操作包在锁内：

```java
public Orders seckill(...) {
    try {
        lock.tryLock(3, 10, TimeUnit.SECONDS);
        redisTemplate.opsForValue().decrement(stockKey);  // Redis 预扣
        Orders order = transactionTemplate.execute(status -> {
            // MySQL 乐观锁扣库存、建单、发延时消息
            return newOrder;
        });  // 事务在这里 commit
    } finally {
        lock.unlock();  // 事务已提交，才释放锁
    }
}
```

执行时序变为：`获取锁 → 业务执行 → 事务提交 → 释放锁`，彻底消除超卖窗口。

**面试怎么说**：`@Transactional` + 手动加锁的经典组合拳陷阱。Spring AOP 代理下，事务提交时机在方法返回后，而锁释放时机在方法返回前——锁保护的事务范围不完整。编程式事务（`TransactionTemplate`）能精确控制事务边界，是"锁内事务"场景的正解。这个坑在面试中很有价值，因为它同时考察了 AOP 代理机制、事务隔离级别、分布式锁三块知识。

---

## 16. 通用异常分支漏回滚 Redis 库存：Redis 与 MySQL 永久不一致

秒杀流程中，Redis 预扣库存（`DECR`）在事务**外面**执行，MySQL 扣库存和建单在事务**里面**执行：

```java
// 事务外
Long remainStock = redisTemplate.opsForValue().decrement(stockKey);

// 事务内
transactionTemplate.execute(status -> {
    productMapper.decreaseStockWithVersion(...);
    ordersMapper.insert(order);
    orderDelayProducer.sendDelayMessage(orderNo);  // 可能抛异常
});
```

**问题**：如果事务内的代码抛出**非业务异常**（比如 MQ 发送失败、数据库连接超时、唯一索引冲突），MySQL 事务会回滚（库存没扣），但 Redis 的 `DECR` 已经执行（库存已扣）——Redis 和 MySQL 库存永久不一致，导致"少卖"。

原来的代码中，只有 `catch (BusinessException)` 分支做了 `INCR` 回补，通用的 `catch (Exception)` 分支只删了用户防重标记，漏掉了库存回补。

**修复**：在通用异常分支补上 `redisTemplate.opsForValue().increment(stockKey)`，并注意 `stockKey` 的作用域——它在 try 块内定义，catch 块访问不到，需要把声明上移到 try 块外。

**面试怎么说**：分布式系统没有分布式事务，Redis 和 MySQL 是两套独立数据源，"先扣 Redis、后扣 MySQL"的流程天然存在失败窗口。所有可能抛出异常的路径都要有对应的补偿逻辑，不能只覆盖业务异常。这类问题的通用解法是：**补偿 + 对账**——补偿解决已知失败路径，对账（定时任务对比 Redis 与 MySQL 库存）兜底未知失败。面试时主动提"对账"会让面试官觉得你有全局视角。

---

## 17. 订单幂等的三层设计：Redis 防重失效后的 DB 兜底

防重复下单最初只有一层：Redis `setIfAbsent(seckill:user:{productId}:{userId})`，TTL 1 小时。代码注释却写着"唯一索引保证幂等"，但 `orders` 表的唯一索引建在了 `order_no`（UUID 随机生成）上——**这个索引永远不会冲突，形同虚设**。真实防重完全依赖 Redis key。

**问题**：Redis 防重 key 只有 1 小时 TTL，过期后同一用户可以重复下单。且如果 Redis 故障（Key 丢失），防重完全失效。

**修复**：`orders` 表新增 `UNIQUE KEY uk_user_product (user_id, product_id)`：

```sql
ALTER TABLE orders ADD UNIQUE KEY uk_user_product (user_id, product_id);
```

DB 唯一索引成为最终兜底——Redis 防重失效时，`INSERT` 会触发 `DuplicateKeyException`，事务回滚（MySQL 库存恢复），再通过前一轮的补偿逻辑回补 Redis 库存，数据最终一致。

**关键副作用**：唯一索引与"取消订单后允许重新抢购"冲突。原来取消订单只是 `UPDATE status=2`，记录仍在表中，用户重新抢购会触发唯一索引冲突——**取消后再也买不到了**。

**解决**：取消订单改为**物理删除**：

```java
// 原：order.setStatus(2); ordersMapper.updateById(order);
ordersMapper.deleteById(order.getId());
```

配合唯一索引：取消 → 删除记录 → 重新抢购 INSERT 新记录，不冲突。而取消超时消息的幂等由"订单不存在 → 直接 ACK"保证。

**面试怎么说**：幂等要分层设计——应用层 Redis 防重挡住绝大多数重复请求，DB 唯一索引兜底极端情况（缓存失效、并发穿透）。但要警惕：**加唯一索引可能破坏既有业务流程**，必须检查所有涉及该表的操作路径（这里是"取消后重购"）。技术选型不只是"加个约束"，要考虑约束与业务逻辑的相互作用。这个案例同时考察了幂等设计、Redis 与 DB 的一致性、以及改表对既有功能的回归影响。

---

## 18. 黑名单自动拉黑机制：限流 → 违规计数 → 阈值拉黑

项目实现了完整的 IP/用户防刷黑名单机制，核心流程是：

```
请求 → 限流触发 → recordViolation()
  ├── IP 违规计数 +1（TTL 10分钟）
  └── 用户违规计数 +1
         ↓
      计数 ≥ 5 → 自动 SADD 到黑名单 Set
```

**实现细节**：
- `RateLimitAspect` 的 `recordViolation()` 同时记录 IP 和已登录用户的违规次数
- 计数器使用 `INCR` 原子操作，首次写入时设置 TTL=600 秒
- 达到阈值（5次）后执行 `SADD` 加入黑名单，并删除计数器
- `BlackListInterceptor` 在请求进入时先检查 IP 和用户是否在黑名单
- 黑名单本身不设 TTL，一旦拉黑需要管理员手动解除

**为什么拦截器顺序是 BlackListInterceptor(order=0) 在 AuthInterceptor(order=1) 之前？**
黑名单检查应该在最外层，即使请求带了合法 Session，只要 IP 或用户被拉黑，直接拒绝，不进入后续任何处理（包括登录校验）。

**为什么黑名单拦截器要排除 /api/blacklist/** 路径，但 AuthInterceptor 不排除？**
如果黑名单拦截器不排除该路径，被拉黑后连"解除黑名单"的接口都访问不了，成了死锁。但该路径必须经过登录拦截器——防止匿名用户随意拉黑/解除他人。

**面试怎么说**：黑名单设计有两个关键点：① **计数器窗口**——10 分钟 TTL 防止正常用户偶尔触发限流被误拉黑，只有持续高频刷接口才会触发阈值；② **拦截器分层**——黑名单检查在登录之前，即使未登录的 IP 也能被拦截。这两个设计体现了"防误伤"和"防绕过"的平衡。

---

## 19. 布隆过滤器双初始化：@PostConstruct + CommandLineRunner 为什么需要两个？

`BloomFilterServiceImpl` 同时实现了 `@PostConstruct` 和 `CommandLineRunner`：

```java
@PostConstruct
public void initBloomFilter() {
    bloomFilter = BloomFilter.create(Funnels.longFunnel(), 10000, 0.01);
}

@Override
public void run(String... args) {
    init();  // 查询全量商品ID，put 到布隆过滤器
}
```

**为什么需要两个？**
- `@PostConstruct`：Spring 容器初始化 Bean 后立即执行，**创建布隆过滤器实例**。这一步只需要内存分配，不需要依赖数据库。
- `CommandLineRunner`：Spring 容器启动完成后、应用接收请求前执行，**预热全量商品ID**。这一步需要查询数据库，依赖 DataSource 和 Mapper 已就绪。

**为什么不直接在 @PostConstruct 里把预热也做了？**
`@PostConstruct` 执行时 MyBatis 的 Mapper 可能还没完全初始化（依赖注入刚完成，但 AOP 代理可能还没完全就绪），此时查数据库可能报错。`CommandLineRunner` 保证在所有 Bean 初始化完成、ApplicationContext 就绪后才执行。

**面试怎么说**：`@PostConstruct` 和 `CommandLineRunner` 的执行时机不同，前者是 Bean 初始化阶段，后者是容器启动完成阶段。如果预热逻辑依赖数据库或其他 Bean，必须用 `CommandLineRunner`。布隆过滤器的"创建实例"（纯内存操作）和"预热数据"（依赖数据库）是两件不同的事，放在两个生命周期里执行更安全。

---

## 20. 缓存失效 invalidateCache 的设计：商品 + 用户双缓存失效通道

### 商品缓存失效

当秒杀成功或订单取消时，需要更新库存，同时清理旧缓存。`ProductServiceImpl.invalidateCache()` 做了三件事：

```java
public void invalidateCache(Long id) {
    // 1. 清除 Redis 缓存
    redisTemplate.delete("product:" + id);
    redisTemplate.delete("seckill:stock:" + id);

    // 2. 失效本地 Caffeine 缓存
    caffeineCache.invalidate("product:" + id);

    // 3. 广播通知其他实例清除本地 Caffeine
    stringRedisTemplate.convertAndSend("cache:invalidate", "product:" + id);
}
```

### 用户缓存失效

用户信息通过 `UserCacheService` 实现了类似的三级缓存机制，有独立的失效频道：

```java
public void invalidateById(Long id) {
    String key = "user:" + id;
    // 1. 清除 Redis 缓存
    stringRedisTemplate.delete(key);
    // 2. 失效本地 Caffeine 缓存
    userCaffeineCache.invalidate(key);
    // 3. 广播通知其他实例
    stringRedisTemplate.convertAndSend("cache:invalidate:user", key);
}
```

**为什么商品和用户用不同的失效频道？**
商品变更（秒杀、取消订单）和用户变更（登录、注册）是两个独立的业务场景，分开频道避免无关消息干扰。而且商品库存变更频率高，用户信息变更频率低，不同频道可以独立调整过期策略。

**为什么 Caffeine 已经设了 30 秒短 TTL，还要主动 invalidate？**
Caffeine 的 30 秒 TTL 是**兜底策略**——即使 Pub/Sub 消息丢失，最坏情况 30 秒后也能读到新数据。但秒杀场景下，库存数据变化后，30 秒的脏数据窗口可能意味着多卖几十件商品。主动 invalidate + Pub/Sub 广播将一致性窗口从 30 秒缩小到毫秒级。

**面试怎么说**：缓存失效设计是"尽力而为 + 兜底"的组合——Pub/Sub 广播尽力做到实时一致，Caffeine 短 TTL 兜底消息丢失的场景。不同业务域（商品、用户）使用独立失效频道，实现解耦和独立扩展。这里没有用"双删"策略（先删缓存 → 更新 DB → 延迟再删一次），因为秒杀场景下 DB 更新是原子操作（`stock = stock + 1`），不需要先删缓存再更新 DB 的复杂流程。

---

## 21. 订单取消的并发控制：deleteById 返回 0 行时直接 return

取消订单时，MQ 死信消息和用户手动取消可能同时触发，两个线程同时执行 `cancelOrder()`：

```java
transactionTemplate.execute(status -> {
    int d = ordersMapper.deleteById(order.getId());
    if (d == 0) {
        // 订单已被其他请求删除，直接返回，不执行后续库存回滚
        return null;
    }
    // 回滚 MySQL 库存
    productMapper.update(null, new UpdateWrapper<Product>()
            .eq("id", productId).setSql("stock = stock + 1"));
    deleted[0] = true;
    return null;
});

if (deleted[0]) {
    // 只有在事务成功删除订单后才执行 Redis 操作
    redisTemplate.opsForValue().increment(stockKey);
    productService.invalidateCache(productId);
    redisTemplate.delete(userKey);
}
```

**关键设计**：
1. MySQL 物理删除使用 `deleteById`，利用数据库行锁保证并发安全——先执行的事务删除成功，后执行的事务 `deleteById` 返回 0
2. `boolean[] deleted` 数组作为标志位，事务提交后检查该标志决定是否执行 Redis 操作
3. 如果不用标志位而直接在事务内执行 Redis 操作，事务回滚会导致 Redis 库存已回滚但 MySQL 没回滚——**库存多卖**

**为什么用 boolean[] 数组而不是普通 boolean 变量？**
Java 要求局部变量在匿名内部类中必须是 `final` 或 effectively final。`boolean[]` 数组的引用是 final 的，但数组元素可以修改。这是 Java 的语法限制，不是设计选择。

**面试怎么说**：并发场景下，deleteById 的返回值就是"是否真的执行了删除"的唯一可信证据。依赖这个返回值而不是"查订单是否存在"来判断，是因为两个线程查到的订单都存在，但只有一个能删除成功。这里的本质是：**用数据库的行锁仲裁并发，而不是在应用层做判断**。

---

## 22. StringRedisTemplate 和 RedisTemplate 混用策略：为什么有的地方用这个，有的地方用那个？

项目里两个 Template 同时存在：

| 使用地方 | 使用的 Template | 原因 |
|---------|----------------|------|
| `ProductServiceImpl` | `RedisTemplate<String, Object>` | 存/取 Product 对象，需要 Jackson 序列化反序列化 |
| `UserCacheService` | `StringRedisTemplate` | UserDTO 以 JSON 字符串存储，直接用 `opsForValue().set/get`，简洁高效 |
| `RateLimitAspect` | `StringRedisTemplate` | Lua 脚本参数必须传纯字符串，避免 Jackson 序列化干扰 |
| `RankServiceImpl` | `StringRedisTemplate` | ZSet member 必须是纯字符串，否则 Jackson 加双引号导致查询失败 |
| `BlackListServiceImpl` | `StringRedisTemplate` | Set member 必须是纯字符串，否则 Jackson 加双引号导致匹配失败 |
| `AuthInterceptor` | `StringRedisTemplate` | 存储 sessionId，纯字符串，不需要对象序列化 |
| `UserServiceImpl` | `StringRedisTemplate` | 存储登录映射 userId→sessionId，纯字符串 |

**规律总结**：
- **存 Java 对象**（如 Product，需要反序列化为对象使用）→ 用 `RedisTemplate<String, Object>` + Jackson 序列化
- **存 JSON 字符串**（如 UserDTO，只需要读字符串或手动反序列化）→ 用 `StringRedisTemplate` + 手动 JSON 转换
- **存纯字符串**（如 sessionId、ZSet member、Set member、Lua 参数）→ 用 `StringRedisTemplate`
- **执行 Lua 脚本** → 必须用 `StringRedisTemplate`

**UserCacheService 为什么用 StringRedisTemplate 而不是 RedisTemplate？**
UserDTO 缓存的使用场景是"写入后偶尔读取"——登录时写入，查询时读取。用 `StringRedisTemplate` 存储 JSON 字符串，读取时用 `ObjectMapper` 手动反序列化。这样做的好处是 Redis 中的数据可读（直接 `GET user:1` 看到 JSON），避免了 Jackson `RedisTemplate` 的类型信息冗余（JSON 中带 `@class` 字段）。

**如果不小心用错了会怎样？**
- `RedisTemplate` 操作 ZSet/Set → member 被 Jackson 序列化成 `"4"`（带双引号），`isMember("4")` 返回 false，`reverseRank("4")` 返回 null
- `RedisTemplate` 执行 Lua 脚本 → `int` 参数被序列化成 JSON 字节，Lua 的 `tonumber()` 返回 nil，限流逻辑静默失效

**面试怎么说**：RedisTemplate 和 StringRedisTemplate 的序列化机制不同，不是"哪个更好"的关系，而是"哪种更适合当前场景"。存对象用 Jackson 序列化方便反序列化，但存纯字符串时 Jackson 的"额外包装"就成了 bug。项目中同时使用两种 Template 是有意为之，不是设计混乱——每个地方根据数据结构选择最合适的序列化方式。对于 UserDTO 缓存，选择 `StringRedisTemplate` + 手动 JSON 转换，是因为 UserDTO 结构简单且需要可读性，避免 Jackson 的类型信息冗余。

---

## 23. 拦截器排除路径设计：黑名单拦截器排除 /api/blacklist/**，但 AuthInterceptor 不排除

```java
// 黑名单拦截器
registry.addInterceptor(blackListInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/user/login", "/api/user/register",
                "/api/blacklist/**", /* 文档路径 */)
        .order(0);

// 登录拦截器
registry.addInterceptor(authInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/user/login", "/api/user/register",
                "/api/test/**", /* 文档路径 */)
        // 注意：/api/blacklist/** 没有排除
        .order(1);
```

**为什么黑名单拦截器要排除 /api/blacklist/**？**
如果不排除，被拉黑的 IP 或用户无法访问"解除黑名单"的接口，成了死锁。即使被拉黑，也应该能通过管理接口解除。

**为什么 AuthInterceptor 不排除 /api/blacklist/**？**
黑名单管理接口（拉黑、解除、查看列表）需要登录权限——防止匿名用户随意拉黑他人。所以 AuthInterceptor 拦截该路径，要求登录后才能操作黑名单。

**拦截器配合效果**：
```
/api/blacklist/add 请求
  → BlackListInterceptor：跳过（排除路径）
  → AuthInterceptor：校验登录，未登录返回 401
  → 正常执行业务逻辑

/api/seckill/1001 请求
  → BlackListInterceptor：检查 IP/用户是否在黑名单
  → AuthInterceptor：校验登录
  → 正常执行业务逻辑
```

**面试怎么说**：两个拦截器的排除路径看似矛盾（一个排除，一个不排除），实际上是精心设计的——黑名单拦截器排除管理接口是"防死锁"，登录拦截器拦截管理接口是"防匿名攻击"。两个拦截器各司其职，通过不同的排除路径组合实现精细的权限控制。

---

## 24. 限流注解差异化配置：为什么秒杀接口限流 10000 次/1秒，而测试接口限流 5 次/10秒？

```java
// 秒杀接口
@RateLimit(windowSec = 1, maxCount = 10000, keyPrefix = "ratelimit:seckill")
public Result<Orders> seckill(...) { ... }

// 测试接口
@RateLimit(windowSec = 10, maxCount = 5, keyPrefix = "ratelimit:ip")
public Result<Map<String, String>> hello() { ... }
```

**为什么秒杀接口限流这么宽松（10000/1s）？**
秒杀接口的限流是**按商品维度**的（`keyPrefix = "ratelimit:seckill"`），所有用户共享同一个限流窗口。10000/1s 的阈值是给所有用户的总配额，防止极端突发流量打爆 Redis。真正的防刷靠的是**黑名单机制**——限流触发后记录违规，5次违规自动拉黑。

**为什么测试接口限流这么严格（5次/10秒）？**
测试接口的限流是**按 IP 维度**的（`keyPrefix = "ratelimit:ip"`），每个 IP 独立计数。5次/10秒的阈值是用来演示限流和黑名单功能的——你在浏览器里连续刷新 5 次就能看到效果。

**限流 + 黑名单的配合逻辑**：
```
限流（宽松，防 Redis 被打爆）
  ↓ 触发限流
违规计数（记录 IP/用户）
  ↓ 5次违规
自动拉黑（持久封禁，需管理员解除）
```

**面试怎么说**：限流和黑名单是两层防护，限流参数按业务场景差异化配置。秒杀接口的限流是"保护系统不被打爆"的底线，不是"防刷"的手段。真正防刷靠的是违规计数 + 自动拉黑。面试官如果问"为什么秒杀接口限流设这么高"，答案是"因为限流只是防 Redis 过载，防刷靠黑名单"——这体现了对防护系统分层设计的理解。

---

## 25. 为什么不用 MyBatis-Plus @Version 注解做乐观锁，而要手写 XML？

MyBatis-Plus 提供了 `@Version` 注解来做乐观锁，`updateById` 时会自动拼接 `WHERE version = #{entity.version}` 和 `SET version = version + 1`。但项目里**没有使用 `@Version`**，而是手写了 XML 的 `decreaseStockWithVersion`：

```java
// ProductMapper.xml
UPDATE product SET stock = stock - 1, version = version + 1
WHERE id = #{id} AND stock > 0 AND version = #{version}
```

**为什么不直接用 `@Version` + `updateById`？**

`@Version` 的乐观锁只能配合 `updateById` 使用，生成的是：

```sql
-- @Version 自动生成的 SQL
UPDATE product SET stock = #{entity.stock}, version = version + 1
WHERE id = #{id} AND version = #{version}
```

注意 `stock = #{entity.stock}`——这是**将库存设为某个具体值**，不是**原子递减**。要实现 `stock = stock - 1`，需要先查出来 (`SELECT stock`)，在 Java 里减 1，再 set 回去。这不是原子的——两个线程读到 stock=10，都减成 9，都 set 9，最终库存是 9 而不是 8（超卖）。

本质原因是：**`stock = stock - 1` 是 DB 级别的原子操作，`@Version` 做不到这一点。**

**为什么 updateById 的"先查后设"在高并发下不安全？**

```
线程A: SELECT stock = 10 → Java: stock - 1 = 9 → UPDATE SET stock = 9
线程B: SELECT stock = 10 → Java: stock - 1 = 9 → UPDATE SET stock = 9
```

虽然 `@Version` 能保证第二个 UPDATE 失败（version 不匹配），但**它会抛 `OptimisticLockException`**，然后整个事务回滚。用户看到的是"秒杀失败"，需要重试。而 `stock = stock - 1` 的原子操作 + `WHERE stock > 0` 则不会出现这个竞态条件——`DECR` 在 DB 层面就完成了减一，`version` 检查只是兜底，不会因为"先查后设"导致非必要的乐观锁冲突。

**手写 XML 的额外优势：`WHERE stock > 0`**

`stock = stock - 1` 配上 `WHERE stock > 0`，在库存为 0 时 UPDATE 影响 0 行，直接返回失败。如果用 `updateById`，即使库存为 0，`stock = 0` 的 UPDATE 也会成功（因为 `WHERE version = 旧值` 成立），需要在 Java 里额外判断 `stock <= 0`。手写 SQL 把"库存是否充足"的检查下沉到 DB 层面，少一趟 Java 判断。

**这个项目里 `@Version` 注解一开始是加了的，为什么后来删了？**

最初代码中 `Product` 实体确实有 `@Version`，但代码审查发现项目中 **没有任何地方调用 `updateById` 更新 Product**——所有库存更新都走 `decreaseStockWithVersion`。`@Version` 只对 `updateById` 生效，加在实体上却不使用，会误导阅读者以为"乐观锁是通过 MyBatis-Plus 插件实现的"，与实际情况不符。删掉 `@Version` 后，代码自文档化：乐观锁就是手写 XML 实现的，一目了然。

**面试怎么说**：这个案例考察的是"框架注解 vs 手写 SQL 的取舍"——不是所有框架功能都适合用，要理解底层原理。`@Version` 适用于"实体整体更新"场景（如修改用户信息、修改商品描述），但**不适用于"字段原子自增/自减"场景**（如库存扣减）。`stock = stock - 1` 是 DB 层面的原子操作，`updateById` 的"先查后设"做不到。而且，框架注解的生效范围有隐含限制（`@Version` 只对 `updateById` 生效），手写 SQL 的控制力更精确，可以自由组合 `WHERE stock > 0` 等条件。面试官听到"我删掉了 `@Version` 因为觉得它没用还误导人"，会认为你理解框架的边界，不是无脑堆注解的人。

---

## 26. 为什么项目中的 RabbitMQ 没有用来削峰？

很多秒杀系统的经典设计是"请求先进 MQ 队列，后端消费者慢慢处理"，把瞬时高并发削平。但本项目中的 RabbitMQ **只做了一件事**：订单超时自动取消（死信队列 + 30 分钟 TTL）。没有参与秒杀主链路的削峰。

**为什么不需要 MQ 削峰？**

因为削峰在 Redis 层已经完成了，不需要 MQ 再削一遍：

```
请求 → 本地 Semaphore(100并发) → Redis DECR(10万QPS) → 业务处理
         ↑ 削第一道                ↑ 削第二道
```

**削峰三层：**

| 层 | 手段 | 效果 | 说明 |
|----|------|------|------|
| **第一层** | 本地 Semaphore | 100 并发上限 | 保护应用自身不被突发流量打挂，超过 100 直接拒绝 |
| **第二层** | Redis DECR 原子扣库存 | 10万+ QPS | 快速过滤 99% 的失败请求，库存为 0 后直接拒绝 |
| **第三层** | 分布式锁 + MySQL 乐观锁 | 串行化扣减 | 最终兜底，保证不超卖 |

三层削完，真实到达业务层（MySQL 建单）的请求量通常只有几十到几百——**MySQL 的写入压力已经很小了**，不需要 MQ 再缓冲。

**如果加 MQ 削峰，会有什么问题？**

```
MQ 削峰的典型流程：
秒杀请求 → 布隆过滤器 → Redis DECR(预扣) → 发 MQ → 消费者落单 → 异步通知用户
```

这个方案引入了三个额外问题：

1. **用户不能立即拿到结果**——请求发出去后要轮询或等回调，用户体验差。本项目是同步落单，用户直接拿到 Order 对象。
2. **消息可靠性的复杂度**——MQ 消息可能丢失（Confirm 回调失败）、可能重复（消费幂等）、可能积压（消费者处理不过来）。项目当前没有这些问题的处理代码。
3. **订单创建有延迟**——MQ 消费有延迟，即使只有几毫秒，在秒杀场景下用户感知明显。

**那为什么很多秒杀项目用 MQ 削峰？**

传统秒杀系统用 MQ 削峰，是因为它们用 MySQL 直接处理秒杀（没有 Redis 预扣），MySQL 扛不住 QPS。这个项目用 Redis DECR 做了预扣，10 万 QPS 的过滤能力已经远高于 MySQL 的承受范围，所以不需要 MQ 再削。

**MQ 在这个项目里的定位是什么？**

MQ 目前只负责**下单后的异步任务**（30 分钟超时取消），不参与主链路：

```
秒杀主链路（同步，用户等待结果）：
  布隆过滤 → Redis 预扣 → 分布式锁 → MySQL 落单 → 返回结果

下单后异步（MQ，用户不等待）：
  发送延时消息 → 30分钟后 → 检查订单 → 未支付则取消
```

**面试怎么说**：秒杀系统不是一定要用 MQ 削峰。削峰的本质是"前置过滤"，MQ 削峰解决的是"MySQL 扛不住 QPS"的问题。如果 Redis 预扣已经能过滤掉绝大部分请求，再叠一层 MQ 削峰就是过度设计。面试官如果问"为什么不用 MQ 削峰"，回答的关键是：**说明当前系统的流量过滤层次（Semaphore → Redis DECR → 分布式锁），每层分别解决了什么问题，然后指出 MQ 削峰适合的场景（MySQL 直连、需要异步化）和这个场景的差异。** 这比直接说"Redis 性能高"更能体现架构设计思维。

---

## 27. 为什么选择 RabbitMQ 而不是 Kafka？

**核心原因：业务场景只需要一个"带 30 分钟延迟的消息"，RabbitMQ 天然支持，Kafka 需要复杂的外围搭建。**

**RabbitMQ 的实现方式（一行配置搞定）：**

```java
// 死信队列：TTL 到期后自动投递到死信交换机
rabbitTemplate.convertAndSend("order.delay.exchange", "order.delay.routing", msg, msg -> {
    msg.getMessageProperties().setExpiration("1800000"); // 30分钟
    return msg;
});
```

**Kafka 要实现同样的 30 分钟延迟，需要：**

```
方案一：时间轮（Timing Wheel）
  在消费者端维护一个本地时间轮，消息先进入时间轮，到时间才投递
  问题：应用重启时间轮丢失，需要持久化 + 恢复逻辑

方案二：外部存储 + 定时扫描
  消息先存数据库，定时任务每分钟扫一次，到期的扔到 Kafka 消费
  问题：引入了数据库依赖，定时精度差

方案三：Kafka Streams 的 Session Window
  通过 Session Window 聚合，模拟延迟投递
  问题：语义复杂，维护成本高，延迟精度受限于 window 配置
```

**RocketMQ 的延迟消息也有限制**——只支持 18 个预定义级别（1s/5s/10s/30s/1m/2m/.../2h），不支持自定义任意时长。RabbitMQ 的 TTL 可以精确到毫秒，灵活性更高。

**关键对比矩阵：**

| 维度 | RabbitMQ | Kafka | 本项目需求 |
|------|----------|-------|------------|
| 延迟消息 | ✅ 原生 TTL + DLX | ❌ 需要时间轮或外部存储 | 必须（30分钟精确延迟） |
| 消息吞吐 | 万级/秒 | 百万级/秒 | 秒杀成功才发消息，每秒几十条，不需要高吞吐 |
| 消息可靠性 | 高（Confirm + 持久化 + ACK） | 高 | 需要（订单取消不能丢） |
| 消费模式 | 拉/推，消息一旦消费即删除 | 拉模式，消息保留在日志中 | 一次性消费，不需要回溯 |
| 运维复杂度 | 低 | 高（需要 ZK，分区管理） | 越低越好 |
| 社区生态 | 成熟，死信、延时等场景覆盖广 | 流处理、实时管道场景覆盖广 | 延时消息是核心需求 |

**Kafka 什么时候更强？**

Kafka 的强项在于：
- 海量日志/事件流（千万级/秒）
- 消息回溯重放（日志保留策略）
- 流式处理（Kafka Streams、Flink 集成）
- 多消费者组（发布/订阅，广播）

本项目没有这些需求——MQ 只是"下单后发一条消息，30分钟后取出来看看有没有支付"，不需要高吞吐、不需要回溯、不需要流处理。

**面试怎么说**：这个问题考察的是"技术选型能力"——不是选最火的，而是选最合适的。回答的关键是：**先明确业务需求（延迟消息 → 需要原生 TTL 支持），再对比候选产品在这个维度的差异（RabbitMQ 点赞、Kafka 差评、RocketMQ 有限制），然后说明其他维度（吞吐量、可靠性、运维成本）在当前场景下不是决定性因素。** 面试官听到"Kafka 不支持原生的延迟消息，需要时间轮或外部存储"会认为你理解这两个产品的本质差异，而不是跟风选 Kafka。

---

## 28. UserDTO 脱敏设计：为什么不能直接返回实体？

早期开发时 Controller 直接返回 `User` 实体，后来发现有两个问题：
1. **安全风险**：User 实体包含 `password` 字段（BCrypt 加密后的 Hash），虽然前端不展示，但接口响应体中会携带，容易被抓包获取
2. **字段膨胀**：实体类可能包含很多后端内部字段（如 `version`、`createTime`、`updateTime`），前端不需要这些，返回它们会增加网络传输量

**解决方案**：创建 `UserDTO`（Data Transfer Object），只包含前端需要的字段：
```java
@Data
public class UserDTO implements Serializable {
    private Long id;       // 用户ID
    private String username; // 用户名
    private LocalDateTime createTime; // 创建时间
    // 没有 password、version 等敏感/内部字段
}
```

Service 层返回 `UserDTO` 而非 `User`，Controller 直接返回 DTO。

**DTO 的三大好处**：
1. **安全**：敏感字段（密码 Hash）不出现在接口响应中
2. **解耦**：实体类字段变更（如加字段）不影响接口契约
3. **简洁**：前端只获取需要的字段，减少数据传输

**面试怎么说**：DTO 是"接口契约"和"内部实现"之间的防火墙。实体类对应数据库表，DTO 对应接口文档，两者职责分离。这样做的好处是：① 数据库字段变更不影响前端接口；② 敏感字段不会意外泄露；③ 可以针对不同场景设计不同的 DTO（如 UserLoginDTO 用于登录、UserProfileDTO 用于个人信息）。面试官听到"DTO 是接口契约"会认为你理解分层设计的本质，不是机械地搬用 DTO 模式。

---

## 29. 用户三级缓存设计：Caffeine + Redis + Pub/Sub 如何配合？

**设计动机**：秒杀系统中，用户信息在多个场景（秒杀下单、答题、查排名）都会用到，直接查库会成为瓶颈。需要缓存。

**三级缓存架构**：
```
请求 → Caffeine（本地缓存，10分钟TTL）
  ↓ 未命中
     → Redis（分布式缓存，30分钟TTL，JSON格式）
        ↓ 未命中
           → MySQL（直接查询）
```

**缓存击穿防护**：用 Redisson 分布式锁 + 双重检查机制：
```java
RLock lock = redissonClient.getLock("lock:user:" + id);
if (lock.tryLock(50, 0, TimeUnit.MILLISECONDS)) {
    try {
        // 双重检查：可能其他线程已经加载了
        UserDTO cached = getFromRedis(key);
        if (cached != null) return cached;
        
        // 从 DB 加载
        UserDTO user = userMapper.selectById(id);
        putToRedis(key, user);      // 写入 Redis
        userCaffeineCache.put(key, user); // 写入 Caffeine
    } finally {
        lock.unlock();
    }
}
```

**多实例一致性**：通过 Redis Pub/Sub 广播失效通知：
- 实例 A 修改用户信息 → 清除自身 Redis 和 Caffeine → 发布 `cache:invalidate:user` 频道
- 实例 B、C 等订阅该频道 → 收到消息后清除本地 Caffeine 中对应的 key
- 兜底：Caffeine 有 10 分钟 TTL，即使消息丢失也会自动过期

**与商品缓存的异同**：
- 相同：都是 Caffeine + Redis + MySQL 三级 + Pub/Sub 失效
- 不同：商品缓存用 `RedisTemplate`（存 Product 对象），用户缓存用 `StringRedisTemplate`（存 JSON 字符串的 UserDTO）
- 不同：商品缓存 TTL 30秒（秒杀场景数据变化频繁），用户缓存 TTL 10分钟（用户信息变化少）

**面试怎么说**：用户缓存的设计思路和商品缓存一致，但根据数据特性做了差异化配置——用户信息变化频率低、数据敏感度高，所以用更长的 TTL 和更安全的存储格式（JSON 字符串而非 Java 序列化）。这个案例体现了"三级缓存不是一刀切，要根据数据特性调整策略"的设计思维。同时缓存击穿防护用 Redisson 锁 + 双重检查，保证高并发下不会击穿到数据库。

---

## 30. 防多地登录的 Redis Session 映射机制

**问题**：用户 A 在设备 1 登录后，又在设备 2 登录。如果没有防护，两个设备都可以同时使用，账号安全性低。

**解决方案**：在 Redis 中维护 `userId → sessionId` 的映射关系：
```java
// 登录时：建立映射
stringRedisTemplate.opsForValue().set(
    "login:session:" + userId, 
    session.getId(), 
    24, TimeUnit.HOURS  // 24小时无操作则过期
);
```

每次请求时，`AuthInterceptor` 校验当前 Session ID 是否与 Redis 中存储的一致：
```java
String storedSessionId = stringRedisTemplate.opsForValue().get("login:session:" + userId);
if (storedSessionId == null) {
    // Redis 映射已过期（24小时未操作），踢下线要求重新登录
    session.invalidate();
    return 401;
} else if (!storedSessionId.equals(session.getId())) {
    // sessionId 不一致 → 账号在其他设备登录，踢下线当前 Session
    session.invalidate();
    return 401;
}
```

**安全设计要点**：
1. **不是自动续期**：Redis 映射 24 小时过期后，用户必须重新登录，防止长期未使用的账号被盗用
2. **踢下线而非共存**：新设备登录会覆盖 Redis 映射，旧设备的请求会被拦截器检测到 sessionId 不一致而踢下线
3. **Session 只存 userId**：配合第 5 节的设计，Session 中只有 userId，密码等敏感信息不存 Session

**面试怎么说**：防多地登录的核心思路是"一个 userId 只绑定一个 sessionId"，通过 Redis 维护这个映射关系。设计上有两个关键决策：① 过期后踢下线而非自动续期——自动续期会让长期未操作的账号永远有效，存在安全隐患；② 踢下线而非共存——一个账号只允许一个活跃会话，避免会话劫持。这个设计的好处是安全性高，代价是用户体验稍差（需要重新登录），但在安全敏感的场景下是正确的取舍。

---

## 31. 限流切面 @Order(1) + 日志切面 @Order(2) 的执行顺序设计

项目有两个 AOP 切面：
- `RateLimitAspect`：限流校验（order=1）
- `LogAspect`：日志记录（order=2）

```java
// RateLimitAspect
@Aspect
@Component
@Order(1)  // 数字小的外层先执行
public class RateLimitAspect { ... }

// LogAspect
@Aspect
@Component
@Order(2)
public class LogAspect { ... }
```

**为什么限流要比日志先执行？**
如果日志切面先执行，被限流的请求也会被记录日志。在秒杀场景下，可能有大量请求被限流（库存不足、重复下单），这些无效请求的日志会污染日志系统，增加排查难度。

**AOP 执行顺序规则**：`@Order` 数值小的切面在外层，执行顺序为：
```
请求进入 → RateLimitAspect.preHandle → LogAspect.preHandle → 业务方法
                                                                     ↓
请求返回 ← RateLimitAspect.postHandle ← LogAspect.postHandle ← 业务方法结束
```

如果被限流，`RateLimitAspect` 直接返回 `false`（不进入后续链），`LogAspect` 的 `preHandle` 不会被调用。

**面试怎么说**：AOP 切面的 `@Order` 设计体现了"责任链优先级"的思维。限流是"准入控制"，应该在最外层——不符合条件的请求直接拒绝，不进入任何后续处理（包括日志、鉴权等）。这样既保护了后续组件（日志系统不会被无效请求淹没），也优化了性能（减少不必要的计算）。这个设计体现了"防御层要在外围"的架构思维——就像防火墙在网络边界，而不是在内部。

---

## 32. 秒杀流程的编程式事务设计：TransactionTemplate 的必要性

**问题**：秒杀流程需要"Redis 预扣 → MySQL 扣减 → 建单 → 发消息"四个操作在同一个事务内。如果用声明式 `@Transactional`，会有"锁释放早于事务提交"的超卖窗口（见第 15 节）。

**解决方案**：用 `TransactionTemplate` 编程式事务，精确控制事务边界：

```java
public SeckillResult seckill(Long productId, Long userId) {
    RLock lock = redissonClient.getLock("seckill:lock:" + productId);
    try {
        lock.lock(10, TimeUnit.SECONDS);
        
        // 1. Redis 预扣（事务外，快速失败）
        Long remainStock = stringRedisTemplate.opsForValue().decrement(stockKey);
        if (remainStock < 0) {
            // 库存不足，回补 Redis
            stringRedisTemplate.opsForValue().increment(stockKey);
            throw new BusinessException("商品已售罄");
        }
        
        // 2. MySQL 操作在编程式事务内
        return transactionTemplate.execute(status -> {
            // MySQL 乐观锁扣库存、建单、发消息
            // 异常时 status.setRollbackOnly() 自动回滚
            return seckillWithMySQL(productId, userId);
        });
    } catch (Exception e) {
        // 3. 异常时回补 Redis 库存
        stringRedisTemplate.opsForValue().increment(stockKey);
        throw e;
    } finally {
        // 4. 事务提交后才释放锁（保证锁的保护范围完整）
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**编程式事务 vs 声明式事务的对比**：

| 维度 | @Transactional | TransactionTemplate |
|------|---------------|-------------------|
| 事务边界 | 方法级（AOP 代理控制） | 代码块级（手动控制） |
| 锁释放时机 | 方法返回前释放（早于事务提交） | 代码块结束后释放（晚于事务提交） |
| 异常控制 | 默认回滚 RuntimeException | 可精确控制回滚条件 |
| 代码侵入性 | 低（注解） | 高（需要显式调用） |
| 适用场景 | 普通业务方法 | 锁内事务、多数据源事务 |

**面试怎么说**：`TransactionTemplate` 的必要性在于"精确控制事务边界"。声明式 `@Transactional` 由 AOP 代理管理，事务在方法返回后才提交，但 `finally` 块中的锁释放发生在方法返回前——锁保护的事务范围不完整。编程式事务可以将事务精确包裹在锁内，确保"事务提交后才释放锁"，彻底消除超卖窗口。虽然编程式事务代码侵入性更高，但在"锁内事务"这类对时序有严格要求的场景下是必须的。

---

## 33. Redis Pub/Sub 跨实例缓存失效：StringRedisTemplate vs RedisTemplate 的序列化陷阱

**问题**：多实例部署时，实例 A 更新了数据并通过 Pub/Sub 广播失效通知，但实例 B 收到通知后无法清除本地缓存——因为缓存 Key 不匹配。

**根因**：Pub/Sub 消息通过 `RedisTemplate.convertAndSend()` 发送时，Jackson 序列化器会对消息内容添加类型信息，导致接收端解析出来的 Key 与缓存 Key 不匹配。

```java
// 错误示范：用 RedisTemplate 发送
redisTemplate.convertAndSend("cache:invalidate", "product:1001");
// Jackson 序列化后实际发送的可能是 "\"product:1001\"" 或带 @class 的 JSON

// 接收端
String key = new String(message.getBody());
// key 变成 "\"product:1001\""（带多余引号），匹配不上 "product:1001"
caffeineCache.invalidate(key); // 失效失败
```

**解决方案**：改用 `StringRedisTemplate` 发送和接收 Pub/Sub 消息：
```java
// 正确做法：用 StringRedisTemplate 发送
stringRedisTemplate.convertAndSend("cache:invalidate", "product:1001");
// 发送的是纯净的字节 "product:1001"，没有多余引号

// 接收端
String key = new String(message.getBody());
// key = "product:1001"，完美匹配
```

**为什么 StringRedisTemplate 不会有这个问题？**
`StringRedisTemplate` 使用 `StringRedisSerializer`，直接将字符串转为字节，不添加任何序列化包装。而 `RedisTemplate` 默认使用 `Jackson2JsonRedisSerializer`，会将字符串序列化为 JSON 格式（添加双引号和可能的类型信息）。

**面试怎么说**：这个问题的本质是"序列化方式的选择"。Redis Pub/Sub 的消息是纯字符串场景，不需要 Jackson 的 JSON 序列化。用 `StringRedisTemplate` 发送和接收可以避免序列化/反序列化的额外开销和潜在问题。这个案例看似简单，但体现了"技术选型要匹配场景"的核心思想——不是所有 Redis 操作都用 `RedisTemplate`，纯字符串场景优先选 `StringRedisTemplate`。同时这个问题也是 Redis 缓存一致性的经典陷阱——如果 Pub/Sub 消息格式不对，多实例缓存一致性就无法保证，只能靠 TTL 兜底，在秒杀场景下会导致脏读。

---

## 34. 订单金额快照设计：为什么存订单表而不是查商品价格？

**问题**：订单表存了 `amount` 字段（订单金额），但商品表已经有 `price` 字段，为什么不直接关联查询商品价格？

**根因**：商品价格是动态变化的，订单需要保持"交易时的价格"。如果订单不存金额，而是通过 `product_id` 关联查询当前商品价格，会出现以下问题：
- 商品调价后，历史订单的金额也会跟着变（不合理）
- 商品被删除后，历史订单无法查询金额
- 订单报表/对账时数据不稳定

**解决方案**：下单时将商品价格复制到订单表（快照设计）：
```java
Orders newOrder = new Orders();
newOrder.setProductId(productId);
newOrder.setAmount(product.getPrice()); // 订单快照
ordersMapper.insert(newOrder);
```

**面试怎么说**：订单金额采用快照设计，下单时从商品表复制价格到订单表，而不是通过外键关联实时查询。因为商品价格是可变的，历史订单应该保留下单时的价格，这是"数据快照 vs 实时关联"的经典设计取舍。类似的设计在电商系统中非常常见——商品信息、优惠券信息、收货地址等都会在下单时做快照，确保订单数据的不可变性和可追溯性。

---

## 35. 支付幂等设计：数据库条件更新 vs Java 判断

**问题**：支付接口需要保证幂等（重复调用只执行一次），我用了数据库条件更新 `UPDATE orders SET status=1 WHERE order_no=? AND status=0`，而不是先 Java 判断 `order.getStatus() != 0` 再更新。

**两种方案对比**：

| 方案 | 代码示例 | 问题 |
|------|---------|------|
| Java 判断 | `if (order.getStatus() != 0) throw ...;` → `update` | 竞态条件：两个请求同时判断通过，都执行 update |
| 条件更新 | `UPDATE ... WHERE status=0` | DB 行锁保证只有一个更新成功 |

**解决方案**：数据库条件更新实现支付幂等：
```sql
UPDATE orders
SET status = 1, pay_time = NOW(), transaction_id = ?
WHERE order_no = ? AND user_id = ? AND status = 0
```
```java
int affected = ordersMapper.payOrder(orderNo, userId, transactionId);
if (affected == 0) {
    throw new BusinessException("订单状态已变更，请刷新重试");
}
```

**面试怎么说**：支付幂等不能只靠 Java 判断，必须下沉到数据库条件更新。因为两个并发支付请求可能同时通过 Java 的状态检查，然后都执行 UPDATE，导致重复支付。而 `UPDATE ... WHERE status=0` 利用数据库行锁和条件匹配，保证只有一个请求能更新成功（affected=1），另一个请求返回 affected=0，从而实现幂等。这个设计同样适用于订单取消——支付和取消竞争时，都是 WHERE status=0 的条件更新，由数据库行锁仲裁唯一赢家。

---

## 36. 订单状态机设计：支付与超时取消的并发竞争

**问题**：用户在第29分59秒点击支付，同时 RabbitMQ 延迟消息在第30分00秒到达触发超时取消。两个操作竞争同一个订单的 `status=0`，如何保证只有一个成功？

**订单状态机**：
```
          ┌──────────────────┐
          │                   ↓
       [创建订单]        status=0(待支付)
          │              │          │
          │         用户支付        超时取消(MQ)
          │              │          │
          │              ↓          ↓
          │      status=1(已支付)  status=2(已取消)
          │              │
          └──────────────┘
```

**解决方案**：支付和取消都使用 `WHERE status=0` 的条件更新：
```sql
-- 支付
UPDATE orders SET status=1, pay_time=NOW(), transaction_id=?
WHERE order_no=? AND user_id=? AND status=0

-- 取消
UPDATE orders SET status=2 WHERE id=? AND status=0
```

数据库行锁保证：两个操作竞争同一行时，只有一个能匹配 `status=0` 并更新成功，另一个返回影响行数 0。

**MQ 消费者兼容**：如果支付成功后 MQ 延迟消息才到达，消费者查询订单 `status != 0`（已是 1），直接 ACK，无需删除消息。

**面试怎么说**：订单状态机是并发安全的核心设计。支付和超时取消是两个并发竞争的操作，我用相同的 `WHERE status=0` 条件更新来处理竞争，由数据库行锁仲裁唯一赢家。这个设计的好处是：1）幂等性——重复支付/取消只有一次成功；2）并发安全——支付与取消竞争时不会出现状态混乱；3）MQ 兼容性——支付成功后 MQ 消息到达，直接 ACK 即可。这种"状态机 + 原子状态流转"的模式在订单系统、库存系统等核心业务中广泛应用。

---

## 37. 秒杀防重的两层设计：Redis SETNX + DB 状态兜底

**问题**：用户防重只靠 Redis SETNX，但 key 有 1 小时 TTL，过期后用户可以重复下单。如何解决？

**方案对比**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| 纯 Redis SETNX | 高性能，微秒级响应 | TTL 过期后失效，Redis 故障无兜底 |
| 纯 DB 查询 | 无过期问题 | 性能差，每次秒杀都要查库 |
| Redis + DB 两层 | 兼顾性能和可靠性 | 代码稍复杂 |

**最终方案**：两层防重机制，各有职责：
```java
// 第一层：Redis SETNX 快速拦截（99% 的请求在此被挡住）
Boolean added = stringRedisTemplate.opsForValue()
        .setIfAbsent(userKey, "1", 1, TimeUnit.HOURS);
if (!added) {
    throw new BusinessException("你已经抢过了");
}

// 第二层：DB 状态查询兜底（防止 Redis TTL 过期或故障）
Orders existing = ordersMapper.selectValidOrder(userId, productId);
if (existing != null) {
    redisTemplate.delete(userKey);
    throw new BusinessException("你已经有该商品的订单");
}
```

```sql
-- 只查有效订单（未支付或已支付），已取消的允许重购
SELECT * FROM orders
WHERE user_id = ? AND product_id = ? AND status IN (0, 1)
LIMIT 1
```

**关键设计**：DB 查询只查 `status IN (0,1)` 的有效订单，已取消（status=2）的不算，允许用户取消后重购。

**面试怎么说**：秒杀防重采用了两层机制。第一层是 Redis SETNX，高性能快速拦截 99% 的重复请求；第二层是 DB 状态查询，作为 Redis TTL 过期或故障后的兜底。需要注意的是，单纯 SELECT + INSERT 在极端并发下仍可能有竞态——两个请求可能同时查到无订单然后都插入。因此严格来说，如果业务要求 100% 幂等，还需要数据库唯一约束或其他原子化方案。当前项目因为有分布式锁串行化 + Redis SETNX 已经能覆盖绝大多数场景，DB 查询只是兜底。这个设计体现了"分层防御、职责清晰"的思想——性能层解决大部分问题，可靠性层处理边缘场景。

---

## 38. 商品缓存分离设计：为什么 Caffeine 存 ProductDTO 而不是完整 Product？

**问题**：Caffeine 缓存中存的是完整 Product 对象（含 stock 字段），但库存是秒杀中变化最频繁的数据，怎么做数据一致性？

**方案分析**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| Caffeine 存完整 Product（含 stock） | 简单，一次查询拿到所有信息 | stock 始终是脏数据，误导调用方 |
| Caffeine 存 ProductDTO（不含 stock），库存单独查 | 数据职责清晰，无脏数据 | 查询商品详情时需额外查一次库存（但实际场景中通常是分开的） |

**最终方案**：Caffeine 存 ProductDTO（脱敏，不含 stock、version），Redis 存完整 Product。
```java
// Caffeine 缓存
Cache<String, ProductDTO> caffeineCache;  // 仅 id, name, price, hot, createTime

// Product → ProductDTO 转换（脱敏高频变更字段）
private ProductDTO toDTO(Product product) {
    ProductDTO dto = new ProductDTO();
    dto.setId(product.getId());
    dto.setName(product.getName());
    dto.setPrice(product.getPrice());
    dto.setHot(product.getHot());
    dto.setCreateTime(product.getCreateTime());
    return dto;
}

// 库存独立查询，走 Redis 实时数据
Integer stock = productService.getStock(id);  // seckill:stock:{id}
```

**为什么这样设计**：

1. **按数据变更频率拆分**：商品基本信息（名称、价格）变化频率极低，适合 Caffeine 缓存；库存每秒钟变化数百次，只适合 Redis 独立存储。
2. **避免脏数据误导**：Caffeine 里的 Product 如果包含 stock，调用方可能误用 `product.getStock()` 获取库存，得到的是 30 秒前的过期值。
3. **减少缓存失效频率**：如果 Caffeine 存了 stock，每次秒杀库存变更都需要失效 Caffeine 缓存，本地缓存频繁失效违背了使用 Caffeine 的初衷。
4. **接口设计清晰**：`getById()` 返回商品基本信息，`getStock()` 返回实时库存，调用方职责分明。

**面试怎么说**：我按数据变更频率拆分缓存。商品基本信息（名称、价格）走三级缓存，Caffeine 存 ProductDTO；库存单独走 Redis，保证实时一致性。核心原因是库存是秒杀中变化最频繁的数据，如果混在 Caffeine 的 Product 对象里，要么是脏数据，要么需要频繁失效缓存，反而得不偿失。这种"按数据变更频率拆分缓存"的设计思路，比"一刀切全部缓存"更合理。面试官问到缓存一致性时，我还会补充说：Redis 存完整 Product 是为了兼容现有数据，Caffeine 存 ProductDTO 是为了避免脏数据，两者职责不同，互不干扰。
