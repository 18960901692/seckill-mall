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
