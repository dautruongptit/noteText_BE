package com.noted.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * Sinh va xac thuc JWT noi bo cua he thong (KHONG lien quan token cua Google).
 *
 * Cac claim duoc dua vao token, ly do can co:
 *  - sub (subject)   : userId, dinh danh chinh
 *  - email           : tien ich, tranh phai query DB o vai noi chi can hien thi
 *  - iss (issuer)     : danh dau token phat hanh boi chinh he thong nay, tranh nham lan
 *                        neu sau nay tich hop them idp/service khac
 *  - jti (token id)    : dinh danh duy nhat cho 1 token cu the -> can thiet de sau nay lam
 *                        blacklist/revoke (VD luu jti da bi thu hoi vao Redis/DB khi logout)
 *  - typ (token_type)  : "access" hoac "refresh" -> BAT BUOC phai co de filter tu choi thang
 *                        truong hop ai do dung nham refresh token de goi API (neu sau nay them
 *                        refresh token dang JWT thay vi opaque token luu DB)
 *  - iat / exp         : thoi diem phat hanh / het han, chuan JWT
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_DRIVE_STATE = "drive_state";
    private static final long DRIVE_STATE_EXPIRATION_MS = 10 * 60 * 1000; // 10 phut - du dai de nguoi dung thao tac tren man hinh consent cua Google

    private static final String ISSUER = "noted-backend";

    private final SecretKey key;
    private final long accessTokenExpirationMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration-ms}") long accessTokenExpirationMs) {
        // Secret can >= 256 bit cho HS256; nen generate bang: openssl rand -base64 32
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public String generateAccessToken(Long userId, String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(ISSUER)
                .id(UUID.randomUUID().toString())      // jti - can cho co che revoke sau nay
                .claim("email", email)
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return Long.valueOf(claims.getSubject());
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    /**
     * Sinh token NGAN HAN (10 phut) dung lam tham so "state" trong luong OAuth2
     * xin quyen Google Drive (xem DriveController.connect()/callback()).
     *
     * VI SAO CAN: endpoint /api/drive/callback duoc GOI BOI TRINH DUYET dieu
     * huong tu Google, KHONG co header Authorization (khong the dua vao JWT
     * filter thong thuong de biet la user nao). Neu chi dung "state=userId"
     * (chuoi so tho), ke tan cong co the tu che 1 URL callback voi state la
     * userId cua NAN NHAN, du du nguoi dung nan nhan bam vao link do bang
     * TAI KHOAN GOOGLE CUA KE TAN CONG -> vo tinh gan Drive cua ke tan cong
     * vao tai khoan Noted cua nan nhan. Ky (sign) state bang JWT dam bao ke
     * tan cong KHONG THE tu tao ra 1 state hop le cho userId bat ky ma khong
     * co secret key cua he thong.
     */
    public String generateDriveStateToken(Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + DRIVE_STATE_EXPIRATION_MS);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(ISSUER)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_DRIVE_STATE)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** Throws IllegalArgumentException neu state khong hop le/het han/sai loai token */
    public Long getUserIdFromDriveStateToken(String state) {
        try {
            Claims claims = parseClaims(state);
            if (!TOKEN_TYPE_DRIVE_STATE.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                throw new IllegalArgumentException("state token sai loai");
            }
            return Long.valueOf(claims.getSubject());
        } catch (JwtException e) {
            throw new IllegalArgumentException("state token khong hop le hoac da het han", e);
        }
    }

    /**
     * Xac thuc chu ky + han su dung + issuer + dung dung loai token "access".
     * Neu sau nay them refresh token dang JWT, filter se goi validateToken() nay va
     * TU DONG tu choi neu ai do dung nham refresh token (typ != "access") de goi API,
     * day chinh la lo hong "thieu claim" da duoc rao soat va va o day.
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);

            if (!ISSUER.equals(claims.getIssuer())) {
                return false;
            }
            if (!TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return false;
            }
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // Bat JwtException chung (gom ca ExpiredJwtException, MalformedJwtException,
            // IncorrectClaimException khi issuer khong khop...) - log o muc WARN trong production,
            // khong throw ra ngoai filter
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
