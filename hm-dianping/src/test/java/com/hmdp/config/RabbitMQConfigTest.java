package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RabbitMQConfigTest {

    @Resource
    RabbitTemplate rabbitTemplate;

    @Test
    void receiveMessage() {
        rabbitTemplate.convertAndSend("dianping.direct", "seckill.order", "测试发送信息");
    }
}