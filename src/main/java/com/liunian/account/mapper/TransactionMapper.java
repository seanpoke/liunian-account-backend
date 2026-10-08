package com.liunian.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liunian.account.entity.TransactionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
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
}
