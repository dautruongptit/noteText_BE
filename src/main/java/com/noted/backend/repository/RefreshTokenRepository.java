package com.noted.backend.repository;

import com.noted.backend.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Thu hoi TOAN BO refresh token cua 1 user - dung khi:
     *  - Nguoi dung bam "Dang xuat tat ca thiet bi"
     *  - He thong PHAT HIEN token bi tai su dung sau khi da revoke (dau hieu bi lo,
     *    xem RefreshTokenServiceImpl.rotate() - "refresh token reuse detection")
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    void revokeAllByUserId(@Param("userId") Long userId);
}
