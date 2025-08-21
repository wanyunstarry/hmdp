package com.hmdp.interceptor;

import com.alibaba.fastjson.JSON;
import com.hmdp.constant.RedisConstants;
import com.hmdp.context.BaseContext;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 拦截一切请求，从而刷新用户token有效期
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (token == null) {
            return true;
        }
        // 2.基于TOKEN获取redis中的用户
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(tokenKey);
        //3.判断用户是否存在
        if (map == null) {
            return true;
        }
        
        // 4.将查询到的hash数据转为UserDTO
        UserDTO userDTO = JSON.parseObject(JSON.toJSONString(map), UserDTO.class);
        //5.存在，保存用户信息到Threadlocal
        BaseContext.saveUser(userDTO);
        // 6.刷新token有效期

        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //7.放行
        return true;
    }

    /**
     * ThreadLocal在set之后会一直存在于内存，必须在请求/线程结束时手动remove
     * 解决内存泄露问题
     *
     * @param request
     * @param response
     * @param handler
     * @param ex
     * @throws Exception
     */

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        BaseContext.removeUser();
    }
}
