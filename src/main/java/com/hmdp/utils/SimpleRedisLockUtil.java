package com.hmdp.utils;

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

    @Override
    public boolean tryLock(long timeoutSec) {
        long id = Thread.currentThread().getId();
        stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, String.valueOf(id), timeoutSec, TimeUnit.SECONDS);
        return false;
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
