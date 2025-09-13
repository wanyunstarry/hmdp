package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.hmdp.constant.RedisConstants;
import com.hmdp.context.BaseContext;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.result.Result;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private FollowMapper followMapper;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = BaseContext.getUser().getId();
        String key = RedisConstants.FOLLOW_COMMONS_KEY + userId;
        if (userId == followId)
            return Result.fail("不能关注自己");
        // 2.判断到底是关注还是取关
        if (isFollow) {
            // 3.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            boolean isSuccess = save(follow);
//            if (isSuccess)
//                stringRedisTemplate.opsForSet().add(key, followId.toString());
        } else {
            // 4.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            Integer i = followMapper.deleteFollowByUserIdAndFollowUserId(userId, followId);
//            if (i != null && i > 0)
//                stringRedisTemplate.opsForSet().remove(key, followId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followId) {
        // 1.获取登录用户
        Long userId = BaseContext.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        /*
        SELECT t1.follow_user_id AS common_follow_id
        FROM tb_follow t1
        JOIN tb_follow t2
        ON t1.follow_user_id = t2.follow_user_id  -- 关联“被关注用户”相同的记录
        WHERE t1.user_id = #{user_id1}  -- 第一个用户的ID
        AND t2.user_id = #{user_id2}; -- 第二个用户的ID
         */
        // 1.获取登录用户
        Long userId = BaseContext.getUser().getId();
        //2.查询共同关注
        List<Long> commonUserIds = followMapper.queryFollowCommons(userId, id);
        if (commonUserIds == null || commonUserIds.isEmpty())
            return Result.ok(Collections.emptyList());
        //3.根据共同关注id查询用户
        List<UserDTO> userDTOList = userService.listByIds(commonUserIds).stream().map(new Function<User, UserDTO>() {
            @Override
            public UserDTO apply(User user) {
                return BeanUtil.copyProperties(user, UserDTO.class);
            }
        }).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }


//    @Override
//    public Result followCommons(Long id) {
//        // 1.获取当前用户
//        Long userId = BaseContext.getUser().getId();
//        String key = "follows:" + userId;
//        // 2.求交集
//        String key2 = "follows:" + id;
//        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
//        if (intersect == null || intersect.isEmpty()) {
//            // 无交集
//            return Result.ok(Collections.emptyList());
//        }
//        // 3.解析id集合
//        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
//        // 4.查询用户
//        List<UserDTO> users = userService.listByIds(ids)
//                .stream()
//                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
//        return Result.ok(users);
//    }

}
