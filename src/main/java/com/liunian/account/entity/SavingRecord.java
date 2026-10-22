package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 储蓄流水（唯一资金事实来源）。物理删除，无 status 作废态。
 *
 * <p>余额永远派生：{@code Σ(in + transfer_in) − Σ(out + transfer_out)}；
 * 组合记录必须成对删除——同表组合（账户转账）用 {@code linkId} 关联，
 * 跨表组合（从储蓄支付 / 到账即存）用 {@code linkedTransactionId} 关联。</p>
 */
@Data
@TableName("saving_record")
public class SavingRecord {

    public static final String DIR_IN = "in";
    public static final String DIR_OUT = "out";
    public static final String DIR_TRANSFER_IN = "transfer_in";
    public static final String DIR_TRANSFER_OUT = "transfer_out";

    /** source：normal=真实资金进出；adjust=「更新余额」生成的校准流水。 */
    public static final String SRC_NORMAL = "normal";
    public static final String SRC_ADJUST = "adjust";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private Long accountId;
    private String direction;
    private String source;
    private Integer amount;
    private LocalDate date;
    private String note;

    /** 操作人 openid（owner 代改他人记录时不改变）。 */
    private String openid;

    /** 操作人昵称快照（成员退出会物理删 family_member 行，历史流水靠它展示）。 */
    private String operatorName;

    /** 同表组合关联键（账户转账的两条记录共享）。 */
    private String linkId;

    /** 跨表组合关联的账单 id（从储蓄支付 / 到账即存）。 */
    private Long linkedTransactionId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
