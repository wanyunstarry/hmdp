package com.hmdp.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;//昵称，默认为userid
    private String icon;//人物头像
}
