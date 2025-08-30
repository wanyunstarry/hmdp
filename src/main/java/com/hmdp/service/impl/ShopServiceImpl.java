package com.hmdp.service.impl;

import com.alibaba.fastjson.JSON;
import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.result.Result;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (shopJson != null && shopJson.length() != 0) {
            //3.存在，直接返回
            Shop shop = JSON.parseObject(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断缓存的是否为空字符串
        //直接用==""判断不行，因为这个比较的是引用地址，而且shopJson可能为null造成NullPointerException
        if ("".equals(shopJson)) {
            return Result.fail("店铺不存在");
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.判断在数据库中是否存在
        if (shop == null) {
            //将空字符串写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
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
