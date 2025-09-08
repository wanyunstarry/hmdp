package com.hmdp.service.impl;

import com.hmdp.context.BaseContext;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.result.Result;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    ISeckillVoucherService seckillVoucherService;
    @Autowired
    RedisIdUtil redisIdUtil;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    /**
     * 优惠卷下单
     *
     * @param voucherId
     * @return
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }//提前把写有lua代码的文件读取出来，利用静态代码块初始化


    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newFixedThreadPool(1);

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = VoucherOrder.builder().id(Long.parseLong(value.get("id").toString()))
                            .voucherId(Long.parseLong(value.get("voucherId").toString()))
                            .userId(Long.parseLong(value.get("userId").toString())).build();
                    //VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4.创建订单
                    createVoucherOrder(voucherOrder);
                    // 5.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream()
                            .read(Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1),
                                    StreamOffset.create(queueName, ReadOffset.from("0")));
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = VoucherOrder.builder().id(Long.parseLong(value.get("id").toString()))
                            .voucherId(Long.parseLong(value.get("voucherId").toString()))
                            .userId(Long.parseLong(value.get("userId").toString())).build();
                    //VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4.创建订单
                    createVoucherOrder(voucherOrder);
                    // 5.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从阻塞队列中去拿信息
//    private BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
//
//    private class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = blockingQueue.take();
//                    // 2.创建订单
//                    createVoucherOrder(voucherOrder);
//                    //前面lua脚本已经保证了线程安全，这里不需要加锁
//                    // 注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }


    /**
     * 判断下单资格
     *
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = BaseContext.getUser().getId();
        //生成订单id
        Long orderId = redisIdUtil.getId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString());
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3.返回订单id
        return Result.ok(orderId);
    }

    public void createVoucherOrder(VoucherOrder voucherOrder) {

        Long voucherId = voucherOrder.getVoucherId();
        //6，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1") //set stock = stock -1
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!success) {
            log.info("库存不足");
        }
        //保存订单
        save(voucherOrder);
    }

//    public Result seckillVoucher(Long voucherId) {
//        //获取用户id
//        Long userId = BaseContext.getUser().getId();
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        int r = result.intValue();
//        // 2.判断结果是否为0
//        if (r != 0) {
//            // 2.1.不为0 ，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//
//        //2.2.为0，有购买资格，把下单信息保存到阻塞队列
//        Long orderId = redisIdUtil.getId("order");
//        //封装订单信息
//        VoucherOrder voucherOrder = VoucherOrder.builder()
//                .userId(BaseContext.getUser().getId())
//                .id(orderId)
//                .voucherId(voucherId)
//                .build();
//        //保存到阻塞队列
//        blockingQueue.add(voucherOrder);
//
//        // 3.返回订单id
//        return Result.ok(orderId);
//    }
}


//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        // 3.判断秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//        // 4.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        // 5.一人一单逻辑
//        Long userId = BaseContext.getUser().getId();
//        //创建锁对象
//        //SimpleRedisLockUtil simpleRedisLockUtil = new SimpleRedisLockUtil
//        //(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock("order:" + userId);
//        //尝试获取锁
//        boolean isLock = lock.tryLock();//无参，失败直接返回false
//        if (!isLock) {
//            return Result.fail("同一用户只允许下一单");
//        }
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5.一人一单逻辑
//        // 5.1.用户id
//        Long userId = BaseContext.getUser().getId();
//        VoucherOrder voucherOrder;
//        Long count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
//        // 5.2.判断是否存在
//        if (count > 0) {
//            return Result.fail("用户已经购买过一次！");
//        }
//
//        //6，扣减库存
//        //这里悲观锁只针对同一个用户，不同用户之间是没有悲观锁的，
//        //该有的秒杀卷的数量的高并发情况下的数据安全问题照样有，所以照样要加乐观锁
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1") //set stock = stock -1
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)
//                .update();
//        //where id = ？ and stock > 0
//
//
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        //7.创建订单
//        voucherOrder = VoucherOrder.builder()
//                .userId(BaseContext.getUser().getId())
//                .id(redisIdUtil.getId("order"))
//                .voucherId(voucherId)
//                .build();
//
//        save(voucherOrder);
//
//
//        return Result.ok(voucherOrder.getId());
//    }
//}
