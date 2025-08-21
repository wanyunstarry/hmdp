package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 用于封装发送所需要的信息，包括对方邮箱号，验证码、主题、内容、引用模板
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EmailDTO implements Serializable {

    private String email;

    private String code;

    private String subject;

    private Map<String, Object> commentMap;

    private String template;

}
