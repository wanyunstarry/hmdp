package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.hmdp.helper.ILock;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLockUtil implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    /*
     锁粒度控制：通过 KEY_PREFIX + name 的设计（如 lock:order 与 lock:payment），不同业务使用独立锁，
     避免全局锁导致的资源争用。例如：订单服务与支付服务若共享同一把锁，即使操作完全无关的资源，也会因锁互斥导致线程阻塞。
     */
    private static final String KEY_PREFIX = "lock:";
    private String name;

    public SimpleRedisLockUtil(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String ID_PREFIX = UUID.randomUUID().toString(true);

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(id))
            stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
