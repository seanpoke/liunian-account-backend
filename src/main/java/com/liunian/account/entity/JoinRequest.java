package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 加入家庭申请 / 审批记录。
 *
 * <p>一条邀请可产生多条申请（1:N）。审批通过后把 nickname/avatar 写入 family_member，
 * 本表作为审批留痕长期保留。</p>
 */
@Data
@TableName("join_request")
public class JoinRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private Long invitationId;
    private String applicantOpenid;
    private String nickname;
    private String avatar;
    private String channel;         // invite / self
    private String status;          // pending / approved / rejected / expired / canceled
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
