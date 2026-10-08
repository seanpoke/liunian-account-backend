package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("family")
public class Family {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String creatorOpenid;
    private String currency;
    private String avatar;
    private Integer memberCount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
