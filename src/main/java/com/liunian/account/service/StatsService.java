package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.TransactionRecord;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.TransactionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    private final TransactionMapper transactionMapper;
    private final FamilyMemberMapper memberMapper;

    public StatsService(TransactionMapper transactionMapper, FamilyMemberMapper memberMapper) {
        this.transactionMapper = transactionMapper;
        this.memberMapper = memberMapper;
    }

    public Map<String, Object> stats(String openid, String period, Integer month, Integer year,
                                     LocalDate start, LocalDate end, String member) {
        FamilyMember me = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid).eq(FamilyMember::getStatus, "active"));
        if (me == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
        Long familyId = me.getFamilyId();

        if ("year".equals(period)) {
            int y = year != null ? year : LocalDate.now().getYear();
            start = LocalDate.of(y, 1, 1);
            end = LocalDate.of(y, 12, 31);
        } else if ("custom".equals(period)) {
            if (start == null || end == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "自定义时段需提供 start/end");
            }
        } else { // month 默认
            int y = year != null ? year : LocalDate.now().getYear();
            int m = month != null ? month : LocalDate.now().getMonthValue();
            YearMonth ym = YearMonth.of(y, m);
            start = ym.atDay(1);
            end = ym.atEndOfMonth();
        }
        if (start == null || end == null) {
            YearMonth ym = YearMonth.now();
            start = ym.atDay(1);
            end = ym.atEndOfMonth();
        }

        String openidFilter = member != null && !member.isBlank() ? member : null;
        Map<String, Object> summary = transactionMapper.summary(familyId, start, end, openidFilter);
        long income = summary.get("income") == null ? 0 : ((Number) summary.get("income")).longValue();
        long expense = summary.get("expense") == null ? 0 : ((Number) summary.get("expense")).longValue();

        List<Map<String, Object>> byCategory = transactionMapper.byCategory(familyId, start, end);
        List<Map<String, Object>> byDay = transactionMapper.byDay(familyId, start, end, openidFilter);
        List<Map<String, Object>> byMember = transactionMapper.byMember(familyId, start, end);

        return Map.of(
                "income", income,
                "expense", expense,
                "balance", income - expense,
                "byCategory", byCategory,
                "byDay", byDay,
                "byMember", byMember);
    }
}
