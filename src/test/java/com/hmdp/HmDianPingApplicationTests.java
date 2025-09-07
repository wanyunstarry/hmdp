package com.hmdp;

import com.hmdp.config.RedissionConfig;
import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheUtil;
import com.hmdp.utils.RedisIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    ShopServiceImpl shopService;

    @Autowired
    CacheUtil cacheUtil;

    @Autowired
    RedisIdUtil redisIdUtil;

    @Autowired
    RedissonClient redissonClient;


    @Test
    void testSaveShop() throws InterruptedException {
        //使用逻辑过期解决缓存击穿首先要向redis中存放热键
//        shopService.saveShopToRedis(1L, 10L);
        Shop shop = shopService.getById(1L);
        cacheUtil.setLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }


    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
/**
 * 用多线程测试生成30000条数据耗时
 * 使用300个线程并发生成ID，每个线程生成100个ID
 */
    void testIdWorker() throws InterruptedException {
        //同步协调在多线程的等待唤醒问题 分线程全部走完之后，主线程再走
        CountDownLatch latch = new CountDownLatch(300);

        // 定义线程任务：生成并打印100个订单ID
        Runnable task = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    // 生成订单ID
                    long id = redisIdUtil.getId("order");
                    System.out.println("id = " + id);
                }
                // 完成一个任务后减少计数器
                latch.countDown();
            }
        };

        // 记录起始时间
        long begin = System.currentTimeMillis();

        // 提交300个并发任务到线程池
        for (int i = 0; i < 300; i++) {
            es.submit(task);  // es为线程池实例
        }

        // 主线程等待所有子任务完成
        latch.await();

        // 计算并输出总耗时
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    private RLock lock;

    @BeforeEach
    void setUp() {
        lock = redissonClient.getLock("order");
    }

    @Test
    void testRedission() throws InterruptedException {
        boolean b = lock.tryLock(1L, TimeUnit.SECONDS);

    }

}
