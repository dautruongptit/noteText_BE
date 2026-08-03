package com.noted.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Refresh token luu duoi dang OPAQUE TOKEN (chuoi random, khong phai JWT).
 *
 * Ly do KHONG dung JWT cho refresh token: JWT la stateless, mot khi da phat hanh
 * thi KHONG THE thu hoi giua chung (tru khi tu xay blacklist rieng, phuc tap hon
 * chinh giai phap nay). Opaque token luu hash trong DB cho phep revoke that su
 * ngay lap tuc (logout, phat hien token bi lo...), phu hop hon cho refresh token
 * vi no song lau (30 ngay) nen rui ro bi lo cao hon nhieu so voi access token (1 gio).
 *
 * Chi luu HASH (SHA-256) cua token trong DB, KHONG luu token goc -> neu DB bi lo
 * (leak), ke tan cong van khong the dung truc tiep de gia mao refresh token.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}
