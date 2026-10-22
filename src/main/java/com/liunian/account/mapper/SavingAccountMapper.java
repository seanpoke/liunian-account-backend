package com.liunian.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liunian.account.entity.SavingAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SavingAccountMapper extends BaseMapper<SavingAccount> {

    /**
     * 取账户并加行锁（{@code SELECT ... FOR UPDATE}）。
     * 储蓄余额是派生值，写操作（存入/取出/转账/支付/更新余额）先锁账户行，
     * 再在锁内 SUM 出余额，等价于「余额原子扣减」，避免两笔并发同时通过校验。
     */
    SavingAccount lockById(@Param("id") Long id);

    /**
     * 含逻辑删除行的全量账户（全局预置 + 本家庭）。
     * 历史流水仍需展示已删除的自定义账户名称。
     */
    List<SavingAccount> listAllIncludingDeleted(@Param("familyId") Long familyId);

    /** 家庭解散时物理删除该家庭的账户行（全局预置行 family_id IS NULL 不受影响）。 */
    int physicallyDeleteByFamily(@Param("familyId") Long familyId);
}
