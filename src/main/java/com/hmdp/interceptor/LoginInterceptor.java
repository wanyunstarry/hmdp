package com.hmdp.interceptor;

import com.alibaba.fastjson.JSON;
import com.hmdp.constant.RedisConstants;
import com.hmdp.context.BaseContext;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

//不能加Competent，拦截器是一个非常轻量级的组件，只有在需要时才会被调用，并且不需要像控制器
// 或服务一样在整个应用程序中可用。因此，将拦截器声明为一个Spring Bean可能会引导致性能下降。
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 拦截部分请求
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断是否需要拦截（ThreadLocal中是否有用户）
        if (BaseContext.getUser() == null) {
            response.setStatus(401);
            return false;
        }

        return true;
    }

}
