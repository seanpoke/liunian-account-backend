package com.liunian.account.wx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liunian.account.common.BizException;
import com.liunian.account.config.WxProperties;
import com.liunian.account.common.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * 微信 access_token 管理：优先读 Redis 缓存，缺失则调 stable_token 刷新并回填。
 */
@Component
public class WxAccessTokenManager {

    private final WxProperties wx;
    private final StringRedisTemplate redis;
    private final RestTemplate rt = new RestTemplate();
    private final ObjectMapper om = new ObjectMapper();
    private static final String KEY = "wx:access_token";

    public WxAccessTokenManager(WxProperties wx, StringRedisTemplate redis) {
        this.wx = wx;
        this.redis = redis;
    }

    public String getToken() {
        String cached = redis.opsForValue().get(KEY);
        if (cached != null) {
            return cached;
        }
        if (wx.isMock()) {
            return "mock_access_token";
        }
        try {
            Map<String, Object> body = Map.of(
                    "grant_type", "client_credential",
                    "appid", wx.getAppid(),
                    "secret", wx.getSecret());
            String resp = rt.postForObject("https://api.weixin.qq.com/cgi-bin/stable_token", body, String.class);
            JsonNode n = om.readTree(resp);
            if (n.has("errcode") && n.get("errcode").asInt() != 0) {
                throw new BizException(ErrorCode.INTERNAL, "获取 access_token 失败:" + resp);
            }
            String token = n.get("access_token").asText();
            int expires = n.get("expires_in").asInt();
            redis.opsForValue().set(KEY, token, Duration.ofSeconds(Math.max(expires - 300, 60)));
            return token;
        } catch (RestClientException | IllegalStateException e) {
            throw new BizException(ErrorCode.INTERNAL, "调用微信接口异常:" + e.getMessage());
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "解析微信响应失败:" + e.getMessage());
        }
    }
}
