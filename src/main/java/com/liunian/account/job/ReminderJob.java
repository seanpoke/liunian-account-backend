package com.liunian.account.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liunian.account.config.WxProperties;
import com.liunian.account.entity.ReminderSetting;
import com.liunian.account.entity.TransactionRecord;
import com.liunian.account.mapper.ReminderSettingMapper;
import com.liunian.account.mapper.TransactionMapper;
import com.liunian.account.wx.WxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);
    private static final String LOCK_KEY = "lock:reminder";

    private final ReminderSettingMapper reminderMapper;
    private final TransactionMapper transactionMapper;
    private final WxService wxService;
    private final WxProperties wx;
    private final StringRedisTemplate redis;

    public ReminderJob(ReminderSettingMapper reminderMapper, TransactionMapper transactionMapper,
                       WxService wxService, WxProperties wx, StringRedisTemplate redis) {
        this.reminderMapper = reminderMapper;
        this.transactionMapper = transactionMapper;
        this.wxService = wxService;
        this.wx = wx;
        this.redis = redis;
    }

    @Scheduled(cron = "${reminder.cron}")
    public void run() {
        String lockVal = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, lockVal, Duration.ofSeconds(55));
        if (Boolean.FALSE.equals(locked)) {
            return; // 其他实例已在执行
        }
        try {
            LocalTime now = LocalTime.now().withSecond(0).withNano(0);
            List<ReminderSetting> due = reminderMapper.selectList(Wrappers.<ReminderSetting>lambdaQuery()
                    .eq(ReminderSetting::getEnabled, 1)
                    .eq(ReminderSetting::getTime, now));
            for (ReminderSetting r : due) {
                try {
                    process(r);
                } catch (Exception e) {
                    log.warn("提醒处理失败 family={} openid={}: {}", r.getFamilyId(), r.getOpenid(), e.getMessage());
                }
            }
        } finally {
            if (lockVal.equals(redis.opsForValue().get(LOCK_KEY))) {
                redis.delete(LOCK_KEY);
            }
        }
    }

    private void process(ReminderSetting r) {
        long todayCount = transactionMapper.selectCount(Wrappers.<TransactionRecord>lambdaQuery()
                .eq(TransactionRecord::getFamilyId, r.getFamilyId())
                .apply("date = CURDATE()"));
        if (todayCount > 0) {
            return; // 当日家庭已有人记账，跳过
        }
        if (r.getSubscribeCount() == null || r.getSubscribeCount() <= 0) {
            return; // 一次性订阅额度已用尽
        }
        if (r.getSubscribeExpireAt() != null && r.getSubscribeExpireAt().isBefore(LocalDateTime.now())) {
            return;
        }
        if (r.getTmplId() == null || r.getTmplId().isBlank()) {
            return;
        }
        Map<String, Object> data = Map.of(
                "thing1", Map.of("value", "今天还没记账哦"),
                "time2", Map.of("value", LocalDateTime.now().toString()));
        wxService.sendSubscribeMessage(r.getOpenid(), r.getTmplId(), "pages/index/index", data);
        r.setSubscribeCount(r.getSubscribeCount() - 1);
        r.setLastSentAt(LocalDateTime.now());
        reminderMapper.updateById(r);
    }
}
