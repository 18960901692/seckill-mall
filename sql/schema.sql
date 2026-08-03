-- 高并发学习商城秒杀系统数据库脚本
-- 数据库名：seckill_mall

CREATE DATABASE IF NOT EXISTS seckill_mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE seckill_mall;

-- 商品表（库存 + 乐观锁 version 字段）
CREATE TABLE IF NOT EXISTS product (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '商品ID',
    name        VARCHAR(128) NOT NULL COMMENT '商品名',
    price       DECIMAL(10,2) NOT NULL COMMENT '价格',
    stock       INT NOT NULL COMMENT '当前库存',
    version     INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    hot         TINYINT DEFAULT 0 COMMENT '是否热点商品(1=永不过期)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 订单表（用户+商品唯一索引兜底幂等，防 Redis 防重失效后的重复下单）
CREATE TABLE IF NOT EXISTS orders (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
    order_no    VARCHAR(64) NOT NULL COMMENT '订单号',
    user_id     BIGINT NOT NULL COMMENT '用户ID',
    product_id  BIGINT NOT NULL COMMENT '商品ID',
    status      TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态: 0未支付 1已支付 2已取消',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_product (user_id, product_id),
    KEY idx_product_ct (product_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 对已存在的表补充唯一索引（幂等兜底，重复执行会因索引已存在而报错，可忽略）
ALTER TABLE orders ADD UNIQUE KEY uk_user_product (user_id, product_id);

-- 用户表（分布式 Session 关联，密码 BCrypt 加密存储）
CREATE TABLE IF NOT EXISTS user (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(64) NOT NULL COMMENT '用户名',
    password    VARCHAR(128) NOT NULL COMMENT 'BCrypt 加密后密文',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 答题记录表（Redis List 异步落库目标）
CREATE TABLE IF NOT EXISTS answer_record (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    user_id     BIGINT NOT NULL COMMENT '用户ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    correct     TINYINT NOT NULL COMMENT '是否正确: 0错误 1正确',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_user_ct (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='答题记录表';

-- 初始化一个秒杀商品
INSERT INTO product (id, name, price, stock, version, hot)
VALUES (1001, 'Java并发编程实战课', 99.00, 100, 0, 1);

-- 初始化管理员用户（密码：admin123）
INSERT INTO user (username, password)
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH');
