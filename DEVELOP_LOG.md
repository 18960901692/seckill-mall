# 开发日志

## 阶段1：项目基础骨架搭建 ✅

**完成时间**：2026-07-30

### 已完成内容
- pom.xml 配置（Spring Boot 3.3.2 + 所有核心依赖）
- application.yaml 配置（MySQL/Redis/RabbitMQ/MyBatis-Plus/Knife4j）
- 公共基础组件：Result、ResultCode、BusinessException、GlobalExceptionHandler
- Entity 实体类：Product、Orders、User、AnswerRecord
- Mapper 接口：ProductMapper、OrdersMapper、UserMapper、AnswerRecordMapper
- 配置类：RedisConfig、CaffeineConfig、MybatisPlusConfig、ThreadPoolConfig、RabbitMQConfig、SessionConfig、WebMvcConfig、Knife4jConfig
- 拦截器：AuthInterceptor（登录校验）
- AOP 切面：RateLimit（限流注解）、RateLimitAspect（滑动窗口限流）、LogAspect（接口日志）
- SQL 建表脚本：sql/schema.sql
- banner.txt 启动横幅

### 编译验证
- `mvn clean compile` ✅ 通过
- IDE 诊断 ✅ 无报错

---

## 阶段2：MySQL 数据层设计与持久化 ✅

**完成时间**：2026-07-30

### 已完成内容
- SQL 脚本完善：4张表（product/orders/user/answer_record）+ 索引
- XML 映射文件：ProductMapper.xml、OrdersMapper.xml、UserMapper.xml、AnswerRecordMapper.xml
- 乐观锁 SQL 统一在 XML 管理（删除 @Update 注解）

### 编译验证
- `mvn clean compile` ✅ 通过

---

## 阶段3：Redis 核心模块 ⏸️ 待开始
