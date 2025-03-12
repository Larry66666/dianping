package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Larry
 *  
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;
    // ============================使用redis的stream作为消息队列异步秒杀===========================
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//
//    private static final String queueName = "stream.orders";
//
//    @PostConstruct
//    private void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }

    // 专门用来处理下单业务
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 1、从消息队列中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1000 STREAMS streams.order >
//                    List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
//                    );
//                    // 2、判断消息获取是否成功
//                    if (messageList == null || messageList.isEmpty()) {
//                        // 2.1 消息获取失败，说明没有消息，进入下一次循环获取消息
//                        continue;
//                    }
//                    // 3、消息获取成功，可以下单
//                    // 将消息转成VoucherOrder对象
//                    MapRecord<String, Object, Object> record = messageList.get(0);
//                    Map<Object, Object> messageMap = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageMap, new VoucherOrder(), true);
//                    handleVoucherOrder(voucherOrder);
//                    // 4、ACK确认 SACK stream.orders g1 id
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                    // 处理异常消息
//                    handlePendingList();
//                }
//            }
//        }
//    }
//
//    private void handlePendingList() {
//        while (true) {
//            try {
//                // 1、从pendingList中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1000 STREAMS streams.order 0
//                List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
//                        Consumer.from("g1", "c1"),
//                        StreamReadOptions.empty().count(1),
//                        StreamOffset.create(queueName, ReadOffset.from("0"))
//                );
//                // 2、判断pendingList中是否有效性
//                if (messageList == null || messageList.isEmpty()) {
//                    // 2.1 pendingList中没有消息，直接结束循环
//                    break;
//                }
//                // 3、pendingList中有消息
//                // 将消息转成VoucherOrder对象
//                MapRecord<String, Object, Object> record = messageList.get(0);
//                Map<Object, Object> messageMap = record.getValue();
//                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageMap, new VoucherOrder(), true);
//                handleVoucherOrder(voucherOrder);
//                // 4、ACK确认 SACK stream.orders g1 id
//                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
//            } catch (Exception e) {
//                log.error("处理订单异常", e);
//                // 这里不用调自己，直接就进入下一次循环，再从pendingList中取，这里只需要休眠一下，防止获取消息太频繁
//                try {
//                    Thread.sleep(20);
//                } catch (InterruptedException ex) {
//                    log.error("线程休眠异常", ex);
//                }
//            }
//        }
//    }
    // =====================使用阻塞队列异步秒杀==============================
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 1.获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常:{}", e);
                }
            }
        }
    }*/

//    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        // 创建锁对象
//        Long userId = voucherOrder.getUserId();
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁
//        boolean isLock = lock.tryLock();
//        if(!isLock) {
//            // 获取锁失败，返回错误
//            log.error("不允许重复下单");
//            return;
//        }
//        try {
//            // 获取代理对象（事务）
//            proxy.createVoucherOrder(voucherOrder);
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            // 2.1不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3.生成订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 4.发送到rabbitMQ
        rabbitTemplate.convertAndSend("dianping.direct", "seckill.order", voucherOrder);

        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if(r != 0) {
            // 2.1不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4用户id
        voucherOrder.setUserId(userId);
        // 2.5代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6放入阻塞队列
        orderTasks.add(voucherOrder);
        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2 判断秒杀是否已经开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3 判断秒杀是否已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已经结束
            return Result.fail("秒杀已经结束！");
        }
        // 4 判断库存是否充足
        if(voucher.getStock() < 1) return Result.fail("库存不足！");
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if(!isLock) {
            // 获取锁失败，返回错误
            return Result.fail("一人限购一单！");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5 一人一单
        // 5.1 查询订单
        Long userId = voucherOrder.getUserId();
        // 5.2 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0) {
            // 已买过
            log.error("每人限购一单！");
            return;
        }
        // 6 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0) // CAS解决超卖问题
                .update();
        if(!success) {
            log.error("库存不足！");
            return;
        }
        // 7 创建订单
        save(voucherOrder);
    }
}
