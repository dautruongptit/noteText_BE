package com.noted.backend.controller;

import com.noted.backend.domain.entity.User;
import com.noted.backend.dto.request.RefreshTokenRequest;
import com.noted.backend.dto.response.TokenPairResponse;
import com.noted.backend.exception.InvalidRefreshTokenException;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.service.RefreshTokenService;
import com.noted.backend.util.CookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final CookieUtil cookieUtil;

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "fullName", user.getFullName(),
                "avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
                "driveConnected", user.isDriveConnected()
        );
    }

    /**
     * Lam moi access token da het han (1 gio) bang refresh token (30 ngay).
     * KHONG require JWT hop le o SecurityConfig (permitAll) vi day chinh la endpoint
     * duoc goi SAU KHI access token da het han - neu bat buoc JWT hop le se tao vong lap
     * khong loi thoat (het han -> can refresh -> nhung refresh lai can JWT con han).
     *
     * Uu tien doc refresh token tu httpOnly cookie (chuan cho web FE); neu khong co
     * cookie, fallback doc tu request body (cho client khong dung duoc cookie, VD
     * goi truc tiep qua Postman/CLI).
     */
    @PostMapping("/refresh")
    public TokenPairResponse refresh(HttpServletRequest httpRequest,
                                      HttpServletResponse httpResponse,
                                      @RequestBody(required = false) RefreshTokenRequest body) {

        String rawToken = cookieUtil.readRefreshTokenFromCookie(httpRequest);
        if (rawToken == null && body != null) {
            rawToken = body.refreshToken();
        }
        if (rawToken == null) {
            throw new InvalidRefreshTokenException("khong tim thay refresh token (cookie hoac body)");
        }

        var result = refreshTokenService.rotate(rawToken);

        // Xoay vong: token cu da bi thu hoi trong rotate(), gan cookie MOI thay the
        cookieUtil.setRefreshTokenCookie(httpResponse, result.newRefreshToken());

        return new TokenPairResponse(result.newAccessToken(), result.accessTokenExpiresInMs());
    }

    /**
     * Logout: thu hoi refresh token hien tai (khong revoke toan bo thiet bi khac,
     * chi dang xuat phien hien tai) + xoa cookie o trinh duyet.
     * Access token (JWT) la stateless nen khong the "xoa" phia server, se tu het han
     * sau toi da 1 gio - chap nhan duoc vi thoi gian song ngan.
     */
    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawToken = cookieUtil.readRefreshTokenFromCookie(httpRequest);
        if (rawToken != null) {
            refreshTokenService.revoke(rawToken);
        }
        cookieUtil.clearRefreshTokenCookie(httpResponse);
        return Map.of("message", "logged_out");
    }

    /** Dang xuat TOAN BO thiet bi (thu hoi tat ca refresh token cua user) */
    @PostMapping("/logout-all")
    public Map<String, String> logoutAll(@AuthenticationPrincipal Long userId, HttpServletResponse httpResponse) {
        refreshTokenService.revokeAllForUser(userId);
        cookieUtil.clearRefreshTokenCookie(httpResponse);
        return Map.of("message", "logged_out_all_devices");
    }
}
