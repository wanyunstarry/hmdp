package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdUtil {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 预生成开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1756684800L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    /**
     * 根据keyPrefix前缀生成对应业务的全局唯一ID
     *
     * @param keyPrefix
     * @return
     */
    public Long getId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String key = "icrId:" + keyPrefix + ":" + now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长 key icrId:order:2025:09:01
        long count = stringRedisTemplate.opsForValue().increment(key);

        //redis中只存低32位
        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

}
