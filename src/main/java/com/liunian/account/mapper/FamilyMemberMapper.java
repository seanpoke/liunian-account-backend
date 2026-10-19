package com.liunian.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liunian.account.entity.FamilyMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface FamilyMemberMapper extends BaseMapper<FamilyMember> {

    /**
     * 批量统计各家庭真实成员数（family.member_count 已移除，改为实时统计）。
     * 返回每行：familyId / cnt。
     */
    @Select("<script>"
            + "SELECT family_id AS familyId, COUNT(*) AS cnt FROM family_member "
            + "WHERE family_id IN "
            + "<foreach collection='familyIds' item='fid' open='(' separator=',' close=')'>#{fid}</foreach> "
            + "GROUP BY family_id"
            + "</script>")
    List<Map<String, Object>> countByFamilies(@Param("familyIds") Collection<Long> familyIds);
}
