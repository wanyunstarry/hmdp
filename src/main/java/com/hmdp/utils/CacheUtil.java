package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.helper.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class CacheUtil {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public <T> void set(String key, T value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(value), time, unit);
    }

    //方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public <T> void setLogicalExpire(String key, T value, Long time, TimeUnit unit) {
        RedisData<T> redisData = new RedisData<>();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <T, C> T queryWithPassThrough(String keyPrefix, C id, Class<T> type, Function<C, T> dbFallback,
                                         Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (json != null && json.length() != 0) {
            //3.存在，直接返回
            return JSON.parseObject(json, type);
        }
        //判断缓存的是否为空字符串
        //直接用==""判断不行，因为这个比较的是引用地址，而且shopJson可能为null造成NullPointerException
        if ("".equals(json)) {
            return null;
        }
        //4.不存在，根据id查询数据库
        T t = dbFallback.apply(id);
        //5.判断在数据库中是否存在
        if (t == null) {
            //将空字符串写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(t), time, unit);
        return t;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <T, C> T queryWithLogicalExpire(String keyPrefix, C id, Class<T> type, Function<C, T> dbFallback,
                                           Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (json == null || json.length() == 0) {
            //3.未命中，返回空
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData<?> redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject dataJson = (JSONObject) redisData.getData(); // 先转 JSONObject
        T t = dataJson.toBean(type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return t;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean islock = trylock(lockKey);
        // 6.2.判断是否获取锁成功
        if (islock) {
            //再次检测redis缓存是否过期（双重检查）
            String latestJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(latestJson)) {
                return t; // 缓存可能被删除，先返回旧数据
            }

            //反序列化为带逻辑过期的数据结构
            RedisData<?> redisLatestData = JSONUtil.toBean(latestJson, RedisData.class);
            JSONObject dataLatestJson = (JSONObject) redisLatestData.getData(); // 先转 JSONObject
            T latestData = dataLatestJson.toBean(type);
            if (redisLatestData.getExpireTime().isAfter(LocalDateTime.now())) {
                return latestData; // 已被其他线程更新，直接返回新数据
            }

            //开一个新的线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    T data = dbFallback.apply(id);

                    // 写入redis
                    setLogicalExpire(key, data, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return t;
    }

    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return flag != null && flag;
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
