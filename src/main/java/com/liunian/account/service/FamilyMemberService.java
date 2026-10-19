package com.liunian.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.TimeSupport;
import com.liunian.account.entity.Family;
import com.liunian.account.entity.FamilyMember;
import com.liunian.account.entity.TransactionRecord;
import com.liunian.account.mapper.FamilyMapper;
import com.liunian.account.mapper.FamilyMemberMapper;
import com.liunian.account.mapper.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 家庭成员。表内只保留真实成员，故所有查询都不再带 status 过滤。
 */
@Service
public class FamilyMemberService {

    private final FamilyMemberMapper memberMapper;
    private final FamilyMapper familyMapper;
    private final TransactionMapper transactionMapper;
    private final FamilyService familyService;
    private final TransactionService transactionService;

    public FamilyMemberService(FamilyMemberMapper memberMapper, FamilyMapper familyMapper,
                               TransactionMapper transactionMapper, FamilyService familyService,
                               TransactionService transactionService) {
        this.memberMapper = memberMapper;
        this.familyMapper = familyMapper;
        this.transactionMapper = transactionMapper;
        this.familyService = familyService;
        this.transactionService = transactionService;
    }

    public FamilyMember requireMember(String openid) {
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .orderByAsc(FamilyMember::getJoinedAt));
        if (members.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "非家庭成员");
        }
        return members.get(0);
    }

    public FamilyMember requireMember(String openid, Long familyId) {
        FamilyMember m = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getOpenid, openid)
                .eq(FamilyMember::getFamilyId, familyId));
        if (m == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "非本家庭成员");
        }
        return m;
    }

    public FamilyMember requireOwner(String openid, Long familyId) {
        FamilyMember m = requireMember(openid, familyId);
        if (!"owner".equals(m.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅创建者可操作");
        }
        return m;
    }

    /** 实时统计家庭成员数（family.member_count 已移除）。 */
    public long countMembers(Long familyId) {
        return memberMapper.selectCount(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId));
    }

    /** 批量统计成员数，避免家庭列表 N+1。 */
    public Map<Long, Long> countMembersByFamilies(Collection<Long> familyIds) {
        Map<Long, Long> result = new HashMap<>();
        if (familyIds == null || familyIds.isEmpty()) {
            return result;
        }
        for (Map<String, Object> row : memberMapper.countByFamilies(familyIds)) {
            Object fid = row.get("familyId");
            Object cnt = row.get("cnt");
            if (fid instanceof Number f && cnt instanceof Number c) {
                result.put(f.longValue(), c.longValue());
            }
        }
        return result;
    }

    public List<Map<String, Object>> list(String openid, Long familyId) {
        requireMember(openid, familyId);
        List<FamilyMember> members = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId)
                .orderByDesc(FamilyMember::getJoinedAt));
        LocalDate monthStart = TimeSupport.today().withDayOfMonth(1);
        return members.stream().map(m -> {
            long monthCount = transactionMapper.selectCount(Wrappers.<TransactionRecord>lambdaQuery()
                    .eq(TransactionRecord::getFamilyId, familyId)
                    .eq(TransactionRecord::getOpenid, m.getOpenid())
                    .eq(TransactionRecord::getStatus, TransactionRecord.STATUS_VALID)
                    .ge(TransactionRecord::getDate, monthStart));
            return Map.<String, Object>of(
                    "memberId", m.getId(),
                    "openid", m.getOpenid(),
                    "nickname", m.getNickname(),
                    "avatar", m.getAvatar() == null ? "" : m.getAvatar(),
                    "role", m.getRole(),
                    "joinedAt", m.getJoinedAt() == null ? "" : m.getJoinedAt().toString(),
                    "monthCount", monthCount);
        }).toList();
    }

    /**
     * 移除成员：作废其在该家庭的流水 → 物理删除成员行（表中只保留真实成员）。
     */
    @Transactional
    public void remove(String ownerOpenid, Long familyId, Long memberId) {
        requireOwner(ownerOpenid, familyId);
        FamilyMember target = memberMapper.selectById(memberId);
        if (target == null || !familyId.equals(target.getFamilyId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "成员不存在");
        }
        if ("owner".equals(target.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能移除创建者");
        }
        transactionService.voidByMember(familyId, target.getOpenid(), "member_removed", ownerOpenid);
        memberMapper.deleteById(memberId);
    }

    @Transactional
    public void transfer(String ownerOpenid, Long familyId, String toOpenid) {
        FamilyMember owner = requireOwner(ownerOpenid, familyId);
        FamilyMember next = memberMapper.selectOne(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getOpenid, toOpenid));
        if (next == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标成员不存在");
        }
        owner.setRole("member");
        owner.setUpdatedAt(TimeSupport.now());
        next.setRole("owner");
        next.setUpdatedAt(TimeSupport.now());
        memberMapper.updateById(owner);
        memberMapper.updateById(next);

        Family f = familyMapper.selectById(familyId);
        if (f != null) {
            f.setCreatorOpenid(toOpenid);
            f.setUpdatedAt(TimeSupport.now());
            familyMapper.updateById(f);
        }
    }

    @Transactional
    public void quit(String openid, Long familyId) {
        FamilyMember me = requireMember(openid, familyId);

        // 退出后剩余的成员（不含本人），按加入时间升序
        List<FamilyMember> others = memberMapper.selectList(Wrappers.<FamilyMember>lambdaQuery()
                .eq(FamilyMember::getFamilyId, familyId)
                .ne(FamilyMember::getOpenid, openid)
                .orderByAsc(FamilyMember::getJoinedAt));

        if (others.isEmpty()) {
            // 家庭仅剩本人 → 彻底解散（账单等关联数据一并删除）
            familyService.dissolveFamily(familyId);
            return;
        }

        // 本人是创建者且还有其他成员 → 转让创建者给最早加入者
        if ("owner".equals(me.getRole())) {
            FamilyMember heir = others.get(0);
            heir.setRole("owner");
            heir.setUpdatedAt(TimeSupport.now());
            memberMapper.updateById(heir);

            Family f = familyMapper.selectById(familyId);
            if (f != null) {
                f.setCreatorOpenid(heir.getOpenid());
                f.setUpdatedAt(TimeSupport.now());
                familyMapper.updateById(f);
            }
        }

        // 退出即作废本人该家庭下的流水（保留数据、不参与查询统计），随后删除成员行
        transactionService.voidByMember(familyId, openid, "member_quit", openid);
        memberMapper.deleteById(me.getId());
    }

    public void updateMe(String openid, Long familyId, String nickname, String avatar) {
        FamilyMember me = requireMember(openid, familyId);
        if (nickname != null) me.setNickname(nickname);
        if (avatar != null) me.setAvatar(avatar);
        me.setUpdatedAt(TimeSupport.now());
        memberMapper.updateById(me);
    }
}
