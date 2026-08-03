package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.RefreshToken;
import com.noted.backend.domain.entity.User;
import com.noted.backend.exception.InvalidRefreshTokenException;
import com.noted.backend.repository.RefreshTokenRepository;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.security.JwtTokenProvider;
import com.noted.backend.service.RefreshTokenService;
import com.noted.backend.util.HashUtil;
import com.noted.backend.util.TokenGeneratorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    @Override
    @Transactional
    public String issue(Long userId) {
        String rawToken = TokenGeneratorUtil.generateOpaqueToken();

        RefreshToken entity = RefreshToken.builder()
                .userId(userId)
                .tokenHash(HashUtil.sha256(rawToken))
                .expiresAt(LocalDateTime.now().plusNanos(refreshTokenExpirationMs * 1_000_000))
                .revoked(false)
                .build();

        refreshTokenRepository.save(entity);
        return rawToken; // CHI luc nay token goc con ton tai trong bo nho, khong bao gio luu lai
    }

    @Override
    @Transactional
    public RotateResult rotate(String rawRefreshToken) {
        String hash = HashUtil.sha256(rawRefreshToken);

        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("token khong ton tai"));

        if (existing.isRevoked()) {
            // REUSE DETECTION: token nay da bi thu hoi truoc do (tuc la da duoc "xoay vong"
            // 1 lan roi) nhung van co ai do dung lai -> rat co the token goc da bi lo/danh cap,
            // ke tan cong va chu that dang cung giu 1 token cu. Phan ung: thu hoi TOAN BO
            // refresh token cua user nay ngay lap tuc, buoc dang nhap lai moi thiet bi.
            log.warn("Phat hien refresh token bi tai su dung (reuse) cho userId={} -> thu hoi toan bo token", existing.getUserId());
            refreshTokenRepository.revokeAllByUserId(existing.getUserId());
            throw new InvalidRefreshTokenException("token da bi thu hoi (phat hien dau hieu bi lo, vui long dang nhap lai)");
        }

        if (existing.isExpired()) {
            throw new InvalidRefreshTokenException("token da het han");
        }

        // Thu hoi token cu (rotation) TRUOC khi phat token moi
        existing.setRevoked(true);
        refreshTokenRepository.save(existing);

        User user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("user khong ton tai"));

        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String newRefreshToken = issue(user.getId());

        return new RotateResult(
                user.getId(),
                user.getEmail(),
                newAccessToken,
                newRefreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs()
        );
    }

    @Override
    @Transactional
    public void revoke(String rawRefreshToken) {
        String hash = HashUtil.sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
        // Khong throw neu khong tim thay - logout voi token da het han/khong ton tai
        // van nen tra ve thanh cong tu goc do UX (nguoi dung chi muon dang xuat)
    }

    @Override
    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
    }
}
