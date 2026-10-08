package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("reminder_setting")
public class ReminderSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private String openid;
    private Integer enabled;       // 0/1
    private LocalTime time;
    private String tmplId;
    private Integer subscribeCount;
    private LocalDateTime subscribeExpireAt;
    private LocalDateTime lastSentAt;
}
