package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.SystemConstants;
import com.hmdp.context.BaseContext;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.result.Result;
import com.hmdp.result.ScrollResult;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog关联的用户
        queryBlogUser(blog);//地址传递
        // 3.查询blog是否被当前用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        if (BaseContext.getUser() == null) return;
        Long userId = BaseContext.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double flag = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(flag != null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        /*
        new Page<>(current, SystemConstants.MAX_PAGE_SIZE) 创建分页对象：
        current 是当前页码
        SystemConstants.MAX_PAGE_SIZE 定义每页最大记录数（例如 5 或 10）
        按点赞数排序后分页展示，保证每次返回的是当前页中点赞最多的博客
         */
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(new Consumer<Blog>() {
            @Override
            public void accept(Blog blog) {
                queryBlogUser(blog);
                isBlogLiked(blog);
            }
        });
        return Result.ok(records);
    }

    /**
     * 点赞
     * TODO 可能存在并发问题
     *
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = BaseContext.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double flag = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //利用score命令判断用户是否存在，如果存在，返回的是分数，不存在，返回null
        if (flag == null) {
            //3.如果未点赞，可以点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户到Redis的set集合  zadd key value score 记录每个用户被哪些用户点赞
            if (isSuccess)
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        } else {
            //4.如果已点赞，取消点赞
            //4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 把用户从Redis的set集合移除
            if (isSuccess)
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    /**
     * 查询博客点赞top5
     *
     * @param id
     * @return
     */
    public Result queryBlogLikes(Long id) {
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> list = top5.stream().map(new Function<String, Long>() {
            @Override
            public Long apply(String s) {
                return Long.parseLong(s);
            }
        }).collect(Collectors.toList());
        // 3.根据用户id查询用户
        List<User> userList = userMapper.queryBlogLikesByUserIds(list);

        List<UserDTO> userDTOList = userList.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = BaseContext.getUser();
        blog.setUserId(user.getId());
        //2.保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = BaseContext.getUser().getId();
        // 当前用户收件箱的key
        String key = RedisConstants.FEED_KEY + userId;
        // 2.查询收件箱 ZREVRANGEBYSCORE key Min Max  LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty())
            return Result.ok();
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int count = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 4.1.获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 4.2.获取分数(时间戳)
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                count++;
            } else {
                minTime = time;
                count = 1;
            }
        }
        count = minTime == max ? count : count + offset;//特殊处理
        // 5.根据id查询blog
        List<Blog> blogList = blogMapper.queryBlogByIds(ids);
        for (Blog blog : blogList) {
            // 5.1.查询blog关联的用户
            queryBlogUser(blog);//地址传递
            // 5.2.查询blog是否被当前用户点赞
            isBlogLiked(blog);
        }
        // 6.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setOffset(count);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    /**
     * 查询blog关联的用户
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
