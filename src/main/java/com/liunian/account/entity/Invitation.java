package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家庭邀请。一条邀请可被多人使用（1:N），有效性 = 未撤销 且 未过期。
 * 申请/审批信息见 {@link JoinRequest}。
 */
@Data
@TableName("invitation")
public class Invitation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private String inviterOpenid;
    private String token;
    private String type;            // share / qr
    private LocalDateTime expireAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
