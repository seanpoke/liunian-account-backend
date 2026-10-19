package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家庭成员。表内只保留「真实成员」，不再有 status/source：
 * 加入申请与审批记录见 {@link JoinRequest}，退出/被移除即物理删除本行。
 */
@Data
@TableName("family_member")
public class FamilyMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private String openid;
    private String nickname;
    private String avatar;
    private String role;        // owner / member
    private LocalDateTime joinedAt;
    private LocalDateTime updatedAt;
}
