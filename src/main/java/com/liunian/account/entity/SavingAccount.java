package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 储蓄账户。余额不落本表（= {@code saving_record} 的 Σ存入+转入 − Σ取出−转出）。
 *
 * <p>{@code familyId == null} 的行是「系统预置模板」（5 条，见 ci/schema.sql），
 * 只作为复制来源；家庭实际使用的是 {@code isSystem=1} 且 {@code familyId} 非空的行，
 * 因为「停用」是家庭级状态，全局单行无法承载 per-family 状态。</p>
 */
@Data
@TableName("saving_account")
public class SavingAccount {

    /** status：1=启用，0=停用（停用后不进新增存取选择列表，余额仍计入总额）。 */
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    /** is_system：1=系统预置（不可删除，只能停用）。 */
    public static final int SYSTEM = 1;
    public static final int CUSTOM = 0;

    /** 默认账户（未选择账户时落到「活期」）。 */
    public static final String DEFAULT_NAME = "活期";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;
    private String name;
    private String icon;
    private String color;
    private Integer sort;
    private Integer isSystem;
    private Integer status;

    /** 逻辑删除标记（MyBatis-Plus 全局 logic-delete-field=deleted）。 */
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
