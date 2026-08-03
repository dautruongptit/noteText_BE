package com.noted.backend.config;

import com.noted.backend.security.JwtAuthenticationFilter;
import com.noted.backend.security.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // API stateless dung JWT, khong dung cookie session -> khong can CSRF
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                // Preflight CORS (OPTIONS) KHONG BAO GIO kem header Authorization
                // (trinh duyet tu gui, khong the gan JWT vao) - PHAI permitAll
                // TUONG MINH o day, dat DAU TIEN trong danh sach rule. Neu thieu
                // dong nay, moi request co preflight (PATCH/DELETE/POST kem JSON -
                // tuc la GAN NHU TOAN BO API cua app nay) se bi chan boi rule
                // ".authenticated()" ngay o buoc OPTIONS, khien trinh duyet bao loi
                // CORS ngay ca khi cau hinh CorsConfigurationSource hoan toan dung.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/oauth2/**", "/login/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                // /refresh duoc goi CHINH XAC khi access token da het han -> khong the
                // bat buoc JWT hop le o day (se tao vong lap khong loi thoat). Bao mat
                // dua vao refresh token trong httpOnly cookie, khong dua vao JWT filter.
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                // Google redirect TRINH DUYET ve day (khong phai fetch/XHR), nen KHONG
                // co header Authorization -> bat buoc phai permitAll, xac thuc dua vao
                // "state" da ky JWT rieng (xem DriveController.callback())
                .requestMatchers(HttpMethod.GET, "/api/drive/callback").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated()
            )

            // Luong OAuth2 login voi Google (dung de xac thuc nguoi dung LAN DAU / lay refresh token)
            .oauth2Login(oauth2 -> oauth2
                .successHandler(oAuth2LoginSuccessHandler)
            )

            // Filter JWT cho tat ca request API sau khi da co access token
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
