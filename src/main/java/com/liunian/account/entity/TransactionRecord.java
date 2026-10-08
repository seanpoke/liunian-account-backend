package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.liunian.account.common.JacksonListTypeHandler;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "transaction", autoResultMap = true)
public class TransactionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private String openid;
    private String type;            // income / expense
    private Integer amount;         // 分，正整数
    private Long categoryId;
    private String categoryName;    // 冗余
    private String categoryIcon;
    private String categoryColor;
    private LocalDate date;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField(typeHandler = JacksonListTypeHandler.class)
    private List<String> images;    // 小票/凭证图地址数组
}
