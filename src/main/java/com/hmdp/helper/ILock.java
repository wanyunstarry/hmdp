package com.hmdp.helper;

public interface ILock {
    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的过期时间,过期自动释放
     * @return true代表获取锁成功 ; false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
