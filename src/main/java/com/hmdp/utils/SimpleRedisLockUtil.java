package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.hmdp.helper.ILock;
import lombok.Builder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
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
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }//提前把写有lua代码的文件读取出来，利用静态代码块初始化

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        // 获取线程标示
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的标示
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(id))
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//    }
}
