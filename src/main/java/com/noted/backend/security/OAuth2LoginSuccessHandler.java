package com.noted.backend.security;

import com.noted.backend.domain.entity.User;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.service.RefreshTokenService;
import com.noted.backend.util.CookieUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Duoc goi sau khi nguoi dung dong y consent tren Google.
 * Tao/cap nhat User trong DB, sinh CAP token cua he thong minh (khong dung Google
 * token cho API noi bo):
 *  - accessToken (JWT, song 1 gio)  -> tra qua query param, FE luu vao memory (JS)
 *  - refreshToken (opaque, song 30 ngay) -> tra qua httpOnly cookie, JS KHONG doc duoc
 *    (giam manh rui ro bi danh cap qua XSS, vi day la token song lau, nguy hiem hon
 *    nhieu neu bi lo so voi access token chi song 1 gio)
 *
 * Luu y: refresh_token cua GOOGLE (de goi Drive API nen, KHAC voi refreshToken noi bo
 * o tren) duoc xu ly rieng trong DriveController vi no can scope "access_type=offline"
 * o mot luong xin quyen rieng (xem DriveController.connect()).
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final CookieUtil cookieUtil;

    @Value("${app.frontend-redirect-url:http://localhost:8445/oauth/callback}")
    private String frontendRedirectUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {

        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        OAuth2User oAuth2User = oauthToken.getPrincipal();

        String googleId = oAuth2User.getAttribute("sub");
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String avatar = oAuth2User.getAttribute("picture");

        User user = userRepository.findByGoogleId(googleId)
                .map(existing -> {
                    existing.setEmail(email);
                    existing.setFullName(name);
                    existing.setAvatarUrl(avatar);
                    return existing;
                })
                .orElseGet(() -> User.builder()
                        .googleId(googleId)
                        .email(email)
                        .fullName(name)
                        .avatarUrl(avatar)
                        .driveConnected(false)
                        .build());

        user = userRepository.save(user);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = refreshTokenService.issue(user.getId());

        cookieUtil.setRefreshTokenCookie(response, refreshToken);

        // Chi truyen accessToken qua query param - refreshToken KHONG bao gio xuat hien
        // o day (chi nam trong cookie httpOnly), tranh lo qua browser history/log
        String redirectUrl = frontendRedirectUrl + "?token=" + accessToken;
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
