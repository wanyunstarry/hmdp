package com.hmdp.result;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;//List<Blog>:小于指定时间戳的笔记集合
    private Long minTime;//本次查询的推送的最小时间戳
    private Integer offset;//偏移量
}
