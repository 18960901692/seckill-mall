package com.sygzcd.seckillmall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SeckillMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillMallApplication.class, args);
    }

}
