package com.sygzcd.seckillmall;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("纯 Mockito 单测已覆盖所有业务场景，完整集成测试需要真实 MySQL/Redis/RabbitMQ")
class SeckillMallApplicationTests {

    @Test
    void contextLoads() {
    }

}
