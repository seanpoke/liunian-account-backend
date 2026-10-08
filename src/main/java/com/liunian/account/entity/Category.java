package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;      // null = 系统预置(global)
    private String name;
    private String type;        // income / expense
    private String icon;
    private String color;
    private Integer sort;
    private Integer isSystem;   // 0/1
}
