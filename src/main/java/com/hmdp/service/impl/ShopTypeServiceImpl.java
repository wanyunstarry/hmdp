package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.result.Result;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

//    @Autowired
//    private ShopTypeMapper shopTypeMapper;

    /**
     * 查询所有商铺类型
     *
     * @return
     */
//    public Result queryTypeList() {
//        //用String实现，opsForValue写法
//        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
//        //1.从redis查询商铺类型缓存
//        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
//        //2.判断是否存在
//        if (StrUtil.isNotBlank(shopTypeJson)) {
//            //3.存在，直接返回
//            List<ShopType> shopTypeList = JSON.parseObject(shopTypeJson, new TypeReference<List<ShopType>>() {
//            });
//            return Result.ok(shopTypeList);
//        }
//        //4.不存在，查询数据库  MybatisPlus的query()拿来用
//        List<ShopType> shopTypeList = lambdaQuery().orderByAsc(ShopType::getSort).list();
//        //5.数据库中不存在，返回错误信息
//        if (shopTypeList == null) {
//            return Result.fail("商铺类型不存在!");
//        }
//        //6.数据库中存在，写入redis
//        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(shopTypeList));
//        //7.返回
//        return Result.ok(shopTypeList);
//    }
    public Result queryTypeList() {
        // opsForList写法
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        // 1. 从Redis查询 商铺类型缓存 , end:-1 表示取全部数据
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 2. 有就直接返回
        if (CollectionUtil.isNotEmpty(shopTypeJson)) {
            // JSON字符串转对象 排序后返回
            List<ShopType> shopTypes = shopTypeJson.stream().map(json -> JSON.parseObject(json, ShopType.class)).collect(Collectors.toList());
            return Result.ok(shopTypes);
        }
        // 3. 没有就向数据库查询 MP的query()拿来用
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 4. 不存在，返回错误
        if (CollectionUtil.isEmpty(shopTypes)) {
            return Result.fail("商铺类型不存在...");
        }
        // 5. 存在， 写入Redis，这里使用Redis的List类型，String类型，就是直接所有都写在一起，对内存开销比较大。
        // 要将List中的每个元素(元素类型ShopType) ，每个元素都要单独转成JSON，使用stream流的map映射
        List<String> shopTypesJson = shopTypes.stream()
                .map(shopType -> JSON.toJSONString(shopType))
                .collect(Collectors.toList());
        // 因为从数据库读出来的时候已经是按照顺序读出来的，这里想要维持顺序必须从右边push，类似队列
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypesJson);
        // 5. 返回
        return Result.ok(shopTypes);
    }
}
