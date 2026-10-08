package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("invitation")
public class Invitation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private String inviterOpenid;
    private String token;
    private String type;            // share / qr
    private Integer used;           // 0/1
    private String applicantOpenid;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime expireAt;
    private String status;          // pending / approved / rejected / expired
    private LocalDateTime createdAt;
}
