package com.noted.backend.config;

import com.noted.backend.security.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Chi ap dung cho duong dan cua endpoint auto-save - "/api/notes/*/content"
        // KHONG khop bat ky endpoint nao khac trong NoteController (GET /{id},
        // GET /{id}/download deu co pattern khac), nen khong can loc them theo HTTP method
        // o day (RateLimitInterceptor van tu kiem tra PATCH mot lan nua de phong thu).
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/notes/*/content");
    }
}
