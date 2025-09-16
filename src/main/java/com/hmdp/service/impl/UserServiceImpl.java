package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.context.BaseContext;
import com.hmdp.dto.EmailDTO;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.result.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.EmailUtil;
import com.hmdp.utils.RegexUtil;
import com.hmdp.constant.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private EmailUtil emailUtil;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码并保存到redis中
     *
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtil.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码 利用hutool生成6位数字验证码字符串
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //session.setAttribute("code", code);
        // 5.发送验证码
        log.info("发送短信验证码成功，验证码：{}", code);//可以用qq邮箱
        //sendMessage(code);
        return Result.ok();
    }

    /**
     * 登录功能
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtil.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.校验验证码
        String redisCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        //Object sessionCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (redisCode == null || !redisCode.equals(code)) {
            //4.不一致，报错
            return Result.fail("验证码错误！");
        }

        //一致，根据手机号查询用户
        // select * from tb_user where phone =? 由 mybatisplus提供单表的增删改查操作
        LambdaQueryWrapper<User> lqw = new LambdaQueryWrapper<>();
        lqw.eq(User::getPhone, phone);
        User user = userMapper.selectOne(lqw);

        //5.判断用户是否存在
        if (user == null) {
            //不存在，则创建
            user = User.builder().phone(phone)
                    .nickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10))
                    .build();
            //保存用户到数据库
            userMapper.insert(user);
        }
        //6.保存用户信息到redis中
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        // 6.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 6.2.将User对象转为Hash存储
        Map<String, Object> map = new HashMap<>();
        Class<UserDTO> aClass = UserDTO.class;
        Field[] fields = aClass.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            try {
                if (field.getType() != String.class)
                    map.put(field.getName(), field.get(userDTO).toString());
                else map.put(field.getName(), field.get(userDTO));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        // 6.2.将User对象转为Hash存储
        Map<String, Object> map1 = JSON.parseObject(JSON.toJSONString(userDTO), Map.class);

        // 6.3.存储
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, map);
        // 6.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 7.返回token

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = BaseContext.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1   offset的值是0-30，从0开始
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = BaseContext.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();


        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        int max = 0;
        for (int k = 0; k <= dayOfMonth - 1; k++) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            long n = num >> k & 1;
            if (n == 1) {
                count++;
            } else {
                max = Math.max(max, count);
                count = 0;
            }
        }
        return Result.ok(max);
    }

    /**
     * 通过Map将所需要发送的内容进行封装，email为对方的邮箱号，
     * subject为本次邮件的主题，最后通过 EmailUtil 的sendHtmlMail方法进行发送即可。
     * CompletableFuture.runAsync()异步执行邮件发送任务
     *
     * @param code
     */
    public void sendMessage(String code) {
        Map<String, Object> map = new HashMap<>();
        map.put("content", code);
        EmailDTO emailDTO = EmailDTO.builder()
                .template("common.html")
                .email("m202510659@xs.ustb.edu.cn")
                .subject("验证码")
                .commentMap(map).build();
        CompletableFuture.runAsync(() -> emailUtil.sendHtmlMail(emailDTO));
    }
}
