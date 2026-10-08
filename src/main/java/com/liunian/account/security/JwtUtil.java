package com.liunian.account.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-hours:168}")
    private long expireHours;

    private SecretKey key;

    @PostConstruct
    public void init() throws Exception {
        // 密钥统一按 SHA-256 派生为 32 字节，满足 JWT HS256 对密钥长度（>=256 bits）的要求，
        // 避免注入的 secret 长度不足或被不可见字符污染时报 WeakKeyException。
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = md.digest(secret.getBytes(StandardCharsets.UTF_8));
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generate(String openid) {
        Date now = new Date();
        return Jwts.builder()
                .subject(openid)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireHours * 3_600_000L))
                .claim("jti", UUID.randomUUID().toString())
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String openid(String token) {
        return parse(token).getSubject();
    }

    public String jti(String token) {
        return parse(token).get("jti", String.class);
    }

    public long getExpireHours() {
        return expireHours;
    }
}
