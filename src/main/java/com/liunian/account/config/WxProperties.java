package com.liunian.account.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "wx")
public class WxProperties {

    private String appid;
    private String secret;
    private boolean mock;
    private String invitePage;
    private String msgTmplId;
    private String envVersion = "release";
}
