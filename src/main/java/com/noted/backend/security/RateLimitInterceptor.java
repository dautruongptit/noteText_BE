package com.noted.backend.security;

import com.noted.backend.exception.RateLimitExceededException;
import com.noted.backend.util.TokenBucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chan spam PATCH /api/notes/{id}/content (auto-save, xem NoteController/
 * SEC-02). Frontend da co debounce 1.3s (useAutoSave.ts, SEC-08), nhung
 * SERVER KHONG DUOC CHI DUA VAO FE de tu bao ve minh - FE co the bi bug,
 * bi bypass, hoac request co the den tu client khong phai trinh duyet chuan
 * (VD goi truc tiep qua script/Postman voi JWT hop le).
 *
 * Gioi han THEO USER (khong theo IP): dung userId lay tu SecurityContext -
 * da duoc JwtAuthenticationFilter set truoc do trong filter chain (xem
 * SecurityConfig), nen luc HandlerInterceptor nay chay (sau toan bo filter
 * chain, truoc khi vao Controller) chac chan da co san.
 *
 * CHI ap dung cho 1 endpoint nay (khong ap dung toan cuc) - day la endpoint
 * DUY NHAT co tan suat goi cao tu ban chat nghiep vu (auto-save).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // 1 TokenBucket rieng cho moi userId - xem gioi han quy mo trong TokenBucket.java.
    // Map nay TANG DAN theo so user distinct tung goi endpoint (khong tu don
    // dep entry cu) - chap nhan duoc o quy mo hien tai (server ca nhan, it
    // user), can luu y neu sau nay so user tang manh.
    private final Map<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Value("${app.rate-limit.autosave-capacity}")
    private long capacity;

    @Value("${app.rate-limit.autosave-refill-interval-ms}")
    private long refillIntervalMs;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"PATCH".equalsIgnoreCase(request.getMethod())) {
            return true; // phong thu - hien pathPattern trong WebMvcConfig da chi khop dung PATCH content
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof Long userId)) {
            return true; // chua xac thuc - de Spring Security xu ly rieng (se tra 401 truoc do), khong lien quan rate limit
        }

        TokenBucket bucket = buckets.computeIfAbsent(userId, id -> new TokenBucket(capacity, refillIntervalMs));

        if (!bucket.tryConsume()) {
            throw new RateLimitExceededException("Ban dang luu qua nhanh, vui long doi mot chut roi thu lai");
        }
        return true;
    }
}
