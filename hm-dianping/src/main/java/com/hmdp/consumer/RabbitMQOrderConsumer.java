package com.hmdp.consumer;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.rabbitmq.client.AMQP;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;

/**
 * rabbitMQ异步处理消息队列中的订单
 * 注意！这里是加锁判断放在MQ中处理，即防止同一用户重复下单的逻辑在这里处理，而不是主线程中，因为
 * 1、提前加锁会严重影响吞吐量
 * 2、RabbitMQ 作为消息队列，主要用于削峰，所有请求立即进入 RabbitMQ 队列，不阻塞请求。消费者从队列拉取订单时才加锁，保证用户订单创建是串行化的，不会重复创建。
 */
@Service
@Slf4j
public class RabbitMQOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RedissonClient redissonClient;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "seckill.order.queue"),
            key = "seckill.order",
            exchange = @Exchange(name = "dianping.direct", type = ExchangeTypes.DIRECT)
    ))
    public void receiveMessage(VoucherOrder voucherOrder, Message message, Channel channel) {
        if(voucherOrder == null) {
            return;
        }

        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("禁止重复下单");
            return; // 如果加锁失败，说明已有订单在处理，直接返回
        }

        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException e) {
            log.error("订单创建失败，消息重新入队", e);
            retryMessage(message, channel);
        } finally {
            lock.unlock(); // 释放锁
        }
    }

    private void retryMessage(Message message, Channel channel) {
        int retryCount = message.getMessageProperties().getHeader("retryCount") == null ? 0
                : (int) message.getMessageProperties().getHeader("retryCount");
        if (retryCount >= 3) {
            log.error("消息重试次数过多，丢弃消息");
            try {
                channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
            } catch (IOException e) {
                log.error("消息丢弃失败", e);
            }
        } else {
            log.info("消息重试次数: {}", retryCount + 1);
            message.getMessageProperties().setHeader("retryCount", retryCount + 1);
            try {
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
            } catch (IOException e) {
                log.error("消息重新入队失败", e);
            }
        }
    }
}
