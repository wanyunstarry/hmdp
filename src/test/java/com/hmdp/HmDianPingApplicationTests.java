package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    ShopServiceImpl shopService;

    @Test
    void testSaveShop() throws InterruptedException {
        //使用逻辑过期解决缓存击穿首先要向redis中存放热键
        shopService.saveShopToRedis(1L, 10L);
    }

}
