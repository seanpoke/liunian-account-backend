package com.liunian.account.wx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.config.WxProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;

/**
 * 微信服务端能力封装：code2Session、订阅消息推送、小程序码。
 */
@Service
public class WxService {

    private final WxProperties wx;
    private final RestTemplate rt = new RestTemplate();
    private final ObjectMapper om = new ObjectMapper();
    private final WxAccessTokenManager tokenManager;

    public WxService(WxProperties wx, WxAccessTokenManager tokenManager) {
        this.wx = wx;
        this.tokenManager = tokenManager;
    }

    /** wx.login 临时 code 换取 openid（mock 模式直接返回 code 去掉前缀） */
    public String code2Session(String code) {
        if (wx.isMock() && code != null && code.startsWith("mock:")) {
            return code.substring(5);
        }
        try {
            String url = String.format(
                    "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                    wx.getAppid(), wx.getSecret(), code);
            String resp = rt.getForObject(url, String.class);
            JsonNode n = om.readTree(resp);
            if (n.has("errcode") && n.get("errcode").asInt() != 0) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "微信登录失败:" + resp);
            }
            return n.get("openid").asText();
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.INTERNAL, "调用微信 code2Session 异常:" + e.getMessage());
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "解析微信响应失败:" + e.getMessage());
        }
    }

    /** 发送订阅消息（一次性订阅，用尽由调用方控制额度） */
    public void sendSubscribeMessage(String openid, String tmplId, String page, Map<String, Object> data) {
        if (wx.isMock()) {
            return; // 本地 mock 不真实推送
        }
        String token = tokenManager.getToken();
        String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + token;
        Map<String, Object> body = Map.of(
                "touser", openid,
                "template_id", tmplId,
                "page", page == null ? wx.getInvitePage() : page,
                "data", data);
        try {
            rt.postForObject(url, body, String.class);
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.INTERNAL, "推送订阅消息异常:" + e.getMessage());
        }
    }

    /** 生成小程序码（getwxacodeunlimit），返回 base64 data url */
    public String getWxaCode(String scene, String page) {
        if (wx.isMock()) {
            return "data:image/png;base64,mock";
        }
        String token = tokenManager.getToken();
        String url = "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=" + token;
        Map<String, Object> body = Map.of(
                "scene", scene,
                "page", page == null ? wx.getInvitePage() : page,
                "check_path", false,
                "env_version", "release");
        try {
            byte[] bytes = rt.postForObject(url, body, byte[].class);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.INTERNAL, "生成小程序码异常:" + e.getMessage());
        }
    }
}
