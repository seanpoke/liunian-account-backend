package com.liunian.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liunian.account.entity.TransactionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface TransactionMapper extends BaseMapper<TransactionRecord> {

    Map<String, Object> summary(@Param("familyId") Long familyId,
                                @Param("start") LocalDate start,
                                @Param("end") LocalDate end,
                                @Param("openid") String openid);

    List<Map<String, Object>> byCategory(@Param("familyId") Long familyId,
                                         @Param("start") LocalDate start,
                                         @Param("end") LocalDate end);

    List<Map<String, Object>> byDay(@Param("familyId") Long familyId,
                                    @Param("start") LocalDate start,
                                    @Param("end") LocalDate end,
                                    @Param("openid") String openid);

    List<Map<String, Object>> byMember(@Param("familyId") Long familyId,
                                       @Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    /**
     * 成员退出时批量作废其流水，但排除「已被储蓄流水引用」的组合记录
     * （从储蓄支付的支出 / 到账即存的收入）：储蓄余额是派生值，作废任一侧都会造成错账。
     */
    int voidByMemberExceptSavingLinked(@Param("familyId") Long familyId,
                                      @Param("openid") String openid,
                                      @Param("reason") String reason,
                                      @Param("operator") String operator,
                                      @Param("now") LocalDateTime now);
}
