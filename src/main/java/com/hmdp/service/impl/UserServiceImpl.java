package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.dto.EmailDTO;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.result.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.EmailUtil;
import com.hmdp.utils.RegexUtils;
import com.hmdp.constant.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
        if (RegexUtils.isPhoneInvalid(phone)) {
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
        if (RegexUtils.isPhoneInvalid(phone)) {
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
        String token = UUID.randomUUID().toString();
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
