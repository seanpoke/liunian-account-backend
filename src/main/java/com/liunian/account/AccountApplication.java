package com.liunian.account;

import com.liunian.account.common.TimeSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class AccountApplication {

    public static void main(String[] args) {
        // 在 Spring 启动前就锁定默认时区：Spring 的调度器/第三方库在容器刷新期就会读取默认时区，
        // 只靠 @Configuration 注入可能偏晚。tz 由 JVM 参数 -Dapp.time-zone 覆盖。
        String zone = System.getProperty("app.time-zone", TimeSupport.DEFAULT_ZONE);
        TimeSupport.setZone(zone);
        TimeZone.setDefault(TimeZone.getTimeZone(TimeSupport.zone()));
        SpringApplication.run(AccountApplication.class, args);
    }
}
