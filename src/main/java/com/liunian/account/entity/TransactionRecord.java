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

    /** 账目状态：1=有效；0=已作废（成员退出/被移除时批量置 0，不参与查询与统计）。 */
    public static final int STATUS_VALID = 1;
    public static final int STATUS_VOID = 0;

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
    /** 业务时间 HH:mm（精确到分钟），历史数据为 null。 */
    private String occurTime;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField(typeHandler = JacksonListTypeHandler.class)
    private List<String> images;    // 小票/凭证图地址数组

    private Integer status;
    private LocalDateTime voidedAt;
    private String voidReason;
    private String voidedBy;
}
