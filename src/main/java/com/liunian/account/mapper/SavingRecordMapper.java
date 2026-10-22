package com.liunian.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liunian.account.entity.SavingRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface SavingRecordMapper extends BaseMapper<SavingRecord> {

    /** 按账户聚合：total_in / total_out / record_count（余额 = total_in − total_out）。 */
    List<Map<String, Object>> statsByAccount(@Param("familyId") Long familyId);

    /** 单账户余额（total_in − total_out），用于写操作前的余额校验。 */
    Map<String, Object> balanceOf(@Param("familyId") Long familyId, @Param("accountId") Long accountId);

    /**
     * 区间内「存入/取出」合计（不含 transfer_in / transfer_out，转账不计入本月净增）。
     * accountId 为空表示全账户。
     */
    Map<String, Object> periodStats(@Param("familyId") Long familyId,
                                    @Param("accountId") Long accountId,
                                    @Param("start") LocalDate start,
                                    @Param("end") LocalDate end);

    /** 同表组合记录（账户转账的两条）。 */
    List<SavingRecord> listByLinkId(@Param("linkId") String linkId);
}
