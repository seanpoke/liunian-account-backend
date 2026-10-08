package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("budget")
public class Budget {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private Integer amount;        // 分，0=未设置
    private String updatedBy;
    private LocalDateTime updatedAt;
}
