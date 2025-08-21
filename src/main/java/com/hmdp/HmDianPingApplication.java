package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dao接口要想被容器扫描到，有两种解决方案:
 * <p>
 * * 方案一：在Dao接口上添加`@Mapper`注解，并且确保Dao处在引导类所在包或其子包中
 * * 该方案的缺点是需要在每一Dao接口中添加注解
 * * 方案二：在引导类上添加`@MapperScan`注解，其属性为所要扫描的Dao所在包
 * * 该方案的好处是只需要写一次，则指定包下的所有Dao接口都能被扫描到，`@Mapper`就可以不写。
 */
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
