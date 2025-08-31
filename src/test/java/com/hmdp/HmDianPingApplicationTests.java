package com.hmdp;

import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    ShopServiceImpl shopService;

    @Autowired
    CacheUtil cacheUtil;

    @Test
    void testSaveShop() throws InterruptedException {
        //使用逻辑过期解决缓存击穿首先要向redis中存放热键
//        shopService.saveShopToRedis(1L, 10L);
        Shop shop = shopService.getById(1L);
        cacheUtil.setLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

}
