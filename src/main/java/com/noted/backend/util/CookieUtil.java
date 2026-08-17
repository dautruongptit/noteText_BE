package com.noted.backend.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Quan ly cookie chua refresh token, tap trung 1 noi de dam bao moi thuoc tinh
 * bao mat (HttpOnly, Secure, SameSite, Path) duoc ap dung NHAT QUAN moi luc.
 *
 * Cac thuoc tinh quan trong:
 *  - HttpOnly=true   : JavaScript phia FE KHONG the doc duoc cookie nay -> chan
 *                        hoan toan viec danh cap refresh token qua XSS
 *  - Path=/api/auth   : cookie CHI duoc gui kem khi goi toi /api/auth/** (VD refresh,
 *                        logout), KHONG gui kem o moi request khac -> giam dien tan
 *                        cong (attack surface) neu co CSRF o endpoint khac
 *  - SameSite=None    : BAT BUOC vi backend (port 8084) va frontend (port 85,
 *                        chay sau nginx reverse proxy) khac
 *                        origin - can Secure=true di kem (yeu cau HTTPS) tru khi dev local
 *  - Secure            : bat theo profile (tat o dev local http, bat o production https)
 *                         qua property app.jwt.refresh-cookie-secure
 */
@Component
public class CookieUtil {

    private static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";

    @Value("${app.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    @Value("${app.jwt.refresh-cookie-secure:false}")
    private boolean secureCookie;

    public void setRefreshTokenCookie(HttpServletResponse response, String rawRefreshToken) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, rawRefreshToken)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(secureCookie ? "None" : "Lax") // Lax khi dev local (khong the dung None neu khong co Secure)
                .path(COOKIE_PATH)
                .maxAge(refreshTokenExpirationMs / 1000)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(secureCookie ? "None" : "Lax")
                .path(COOKIE_PATH)
                .maxAge(0) // xoa ngay lap tuc
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }

    public String readRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName()) && StringUtils.hasText(c.getValue())) {
                return c.getValue();
            }
        }
        return null;
    }
}
