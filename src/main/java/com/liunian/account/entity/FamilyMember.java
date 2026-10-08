package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
    private String status;      // pending / active / removed
    private String source;      // invite / self
    private LocalDateTime joinedAt;
    private LocalDateTime updatedAt;
}
