package com.hmdp.service.impl;

import com.hmdp.context.BaseContext;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.result.Result;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdUtil;
import com.hmdp.utils.SimpleRedisLockUtil;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
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

    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3.判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        // 4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        // 5.一人一单逻辑
        Long userId = BaseContext.getUser().getId();
        //创建锁对象
        //SimpleRedisLockUtil simpleRedisLockUtil = new SimpleRedisLockUtil
        //(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("order:" + userId);
        //尝试获取锁
        boolean isLock = lock.tryLock();//无参，失败直接返回false
        if (!isLock) {
            return Result.fail("同一用户只允许下一单");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = BaseContext.getUser().getId();
        VoucherOrder voucherOrder;
        Long count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }

        //6，扣减库存
        //这里悲观锁只针对同一个用户，不同用户之间是没有悲观锁的，
        //该有的秒杀卷的数量的高并发情况下的数据安全问题照样有，所以照样要加乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1") //set stock = stock -1
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        //where id = ？ and stock > 0


        if (!success) {
            return Result.fail("库存不足");
        }
        //7.创建订单
        voucherOrder = VoucherOrder.builder()
                .userId(BaseContext.getUser().getId())
                .id(redisIdUtil.getId("order"))
                .voucherId(voucherId)
                .build();

        save(voucherOrder);


        return Result.ok(voucherOrder.getId());
    }
}
