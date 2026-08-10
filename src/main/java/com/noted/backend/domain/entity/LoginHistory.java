package com.noted.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "login_histories",
        indexes = {
                @Index(name = "idx_lh_user_time", columnList = "user_id, login_at"),
                @Index(name = "idx_lh_ip",        columnList = "ip_address"),
                @Index(name = "idx_lh_success",   columnList = "user_id, is_success")
        })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LoginHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** IPv4 hoac IPv6 (lay tu X-Forwarded-For hoac RemoteAddr). */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** Chuoi User-Agent day du tu HTTP header. */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** Mobile | Desktop | Tablet | Unknown. */
    @Column(name = "device_type", length = 30)
    private String deviceType;

    /** He dieu hanh: Windows 11, macOS 14, Android 14, iOS 17. */
    @Column(name = "os", length = 100)
    private String os;

    /** Trinh duyet: Chrome 124, Safari 17, Firefox 125. */
    @Column(name = "browser", length = 100)
    private String browser;

    @Column(name = "country", length = 100)
    private String country;

    /** 1 = thanh cong, 0 = that bai. */
    @Column(name = "is_success", nullable = false)
    private Boolean isSuccess;

    /**
     * Ly do that bai. NULL neu thanh cong.
     * Enum: WRONG_PASSWORD | ACCOUNT_LOCKED | ACCOUNT_INACTIVE
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 200)
    private FailureReason failureReason;

    public enum FailureReason {
        WRONG_PASSWORD,
        ACCOUNT_LOCKED,
        ACCOUNT_INACTIVE
    }

    @Column(name = "login_at", nullable = false, updatable = false)
    private LocalDateTime loginAt;

    @PrePersist
    protected void onCreate() {
        if (loginAt == null) loginAt = LocalDateTime.now();
    }
}
