package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.helper.RedisData;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.result.Result;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public Result queryById(Long id) {
        //解决缓存穿透
        //   Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }
        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (shopJson == null || shopJson.length() == 0) {
            //3.未命中，返回空
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData<Shop> redisData = JSON.parseObject(shopJson, new TypeReference<RedisData<Shop>>() {
        });
        Shop shop = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        boolean islock = trylock(RedisConstants.LOCK_SHOP_KEY + id);
        // 6.2.判断是否获取锁成功
        if (islock) {
            //再次检测redis缓存是否过期（双重检查）
            String latestJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(latestJson)) {
                return shop; // 缓存可能被删除，先返回旧数据
            }

            //反序列化为带逻辑过期的数据结构
            RedisData<Shop> redisLatestData = JSON.parseObject(latestJson, new TypeReference<RedisData<Shop>>() {
            });
            Shop latestShop = redisLatestData.getData();
            if (redisLatestData.getExpireTime().isAfter(LocalDateTime.now())) {
                return latestShop; // 已被其他线程更新，直接返回新数据
            }

            //开一个新的线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return shop;
    }

    private Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (shopJson != null && shopJson.length() != 0) {
            //3.存在，直接返回
            return JSON.parseObject(shopJson, Shop.class);
        }
        //判断缓存的是否为空字符串
        //直接用==""判断不行，因为这个比较的是引用地址，而且shopJson可能为null造成NullPointerException
        if ("".equals(shopJson)) {
            return null;
        }
        // 4.实现缓存重建
        Shop shop = null;
        try {
            //4.1 获取互斥锁
            boolean trylock = trylock(RedisConstants.LOCK_SHOP_KEY + id);
            // 4.2 判断否获取成功
            if (!trylock) {
                //4.3 失败，则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 成功，根据id查询数据库
            shop = getById(id);
            //5.判断在数据库中是否存在
            if (shop == null) {
                //将空字符串写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unLock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return shop;
    }

    private Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (shopJson != null && shopJson.length() != 0) {
            //3.存在，直接返回
            return JSON.parseObject(shopJson, Shop.class);
        }
        //判断缓存的是否为空字符串
        //直接用==""判断不行，因为这个比较的是引用地址，而且shopJson可能为null造成NullPointerException
        if ("".equals(shopJson)) {
            return null;
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.判断在数据库中是否存在
        if (shop == null) {
            //将空字符串写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return flag != null && flag;
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id, Long ttl) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(ttl));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSON.toJSONString(redisData));
    }


    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = RedisConstants.CACHE_SHOP_KEY + shop.getId();
        //1.更新数据库
        updateById(shop);
        //2.删除redis
        stringRedisTemplate.delete(key);
        return Result.ok();
    }


}
