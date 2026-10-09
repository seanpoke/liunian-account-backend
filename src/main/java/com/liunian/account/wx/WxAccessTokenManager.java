package com.liunian.account.wx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.config.WxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * 微信 access_token 管理：优先读 Redis 缓存，缺失则调 stable_token 刷新并回填。
 */
@Component
public class WxAccessTokenManager {

    private static final Logger log = LoggerFactory.getLogger(WxAccessTokenManager.class);

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
            // 必须按 JSON 发送；RestTemplate 收到 Map 默认会发 form 表单，微信会拒绝
            String resp = rt.postForObject("https://api.weixin.qq.com/cgi-bin/stable_token",
                    jsonEntity(body), String.class);
            JsonNode n = om.readTree(resp);
            if (n.has("errcode") && n.get("errcode").asInt() != 0) {
                log.error("[WxToken] 微信返回业务错误: {}", resp);
                throw new BizException(ErrorCode.INTERNAL, "获取 access_token 失败:" + resp);
            }
            String token = n.get("access_token").asText();
            int expires = n.get("expires_in").asInt();
            redis.opsForValue().set(KEY, token, Duration.ofSeconds(Math.max(expires - 300, 60)));
            log.info("[WxToken] access_token 刷新成功, expires={}s", expires);
            return token;
        } catch (HttpStatusCodeException e) {
            // 微信返回非 2xx（如 412/400/401），把状态码+响应体打出来，否则无法定位
            log.error("[WxToken] 调用 stable_token 失败: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new BizException(ErrorCode.INTERNAL, "调用微信接口异常:" + e.getMessage());
        } catch (RestClientException | IllegalStateException e) {
            log.error("[WxToken] 调用 stable_token 异常", e);
            throw new BizException(ErrorCode.INTERNAL, "调用微信接口异常:" + e.getMessage());
        } catch (Exception e) {
            log.error("[WxToken] 解析微信响应失败", e);
            throw new BizException(ErrorCode.INTERNAL, "解析微信响应失败:" + e.getMessage());
        }
    }

    private HttpEntity<String> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return new HttpEntity<>(om.writeValueAsString(body), headers);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "序列化微信请求体失败:" + e.getMessage());
        }
    }
}
