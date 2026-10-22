package com.liunian.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;      // null = 系统预置(global)
    private String name;
    private String type;        // income / expense
    private String code;        // 分类语义编码，如 food
    private String icon;        // emoji，如 🍜
    private String color;
    private Integer sort;
    private Integer isSystem;   // 0/1

    /**
     * 逻辑删除标记（MyBatis-Plus 全局 logic-delete-field=deleted 自动生效）：
     * 0=有效，1=已删除。删除分类只置标记、不物理删行，历史账单仍靠
     * transaction 里的 category_name/icon/color 快照正常展示。
     */
    private Integer deleted;
}
