package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.RefreshToken;
import com.noted.backend.domain.entity.User;
import com.noted.backend.exception.InvalidRefreshTokenException;
import com.noted.backend.repository.RefreshTokenRepository;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Trong tam: co che ROTATION va REUSE DETECTION (xem SEC-06) - day la phan
 * bao mat quan trong nhat cua refresh token, can test ky hon cac CRUD thong thuong.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceImplTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private RefreshTokenServiceImpl refreshTokenService;

    private static final Long USER_ID = 7L;
    private static final long EXPIRATION_MS = 2_592_000_000L; // 30 ngay, khop application.yml

    @BeforeEach
    void setUp() {
        // @Value khong duoc Spring inject trong unit test thuan Mockito - gan tay
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenExpirationMs", EXPIRATION_MS);
    }

    @Test
    void issue_luuHashCuaTokenVaoDb_KHONG_luuTokenGoc() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);

        String rawToken = refreshTokenService.issue(USER_ID);

        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(rawToken).isNotBlank();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.isRevoked()).isFalse();
        // DB chi duoc luu HASH, tuyet doi khong phai token goc (kiem tra token
        // goc KHONG xuat hien nguyen van trong bat ky field nao duoc luu)
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(saved.getTokenHash()).hasSize(64); // do dai chuan cua SHA-256 hex
    }

    @Test
    void rotate_thuHoiTokenCu_vaPhatHanhCapTokenMoi_khiTokenHopLe() {
        RefreshToken existing = validToken(USER_ID, false);
        User user = userWith(USER_ID, "a@example.com");

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(USER_ID, "a@example.com")).thenReturn("new-access-token");
        when(jwtTokenProvider.getAccessTokenExpirationMs()).thenReturn(3_600_000L);

        var result = refreshTokenService.rotate("raw-token-tu-client");

        // Token CU phai bi danh dau revoked=true NGAY (rotation)
        assertThat(existing.isRevoked()).isTrue();
        verify(refreshTokenRepository, atLeastOnce()).save(existing);

        // Cap token MOI duoc phat hanh dung
        assertThat(result.newAccessToken()).isEqualTo("new-access-token");
        assertThat(result.newRefreshToken()).isNotBlank();
        assertThat(result.userId()).isEqualTo(USER_ID);

        // issue() ben trong rotate() phai tao ra 1 RefreshToken entity MOI khac voi token cu
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class)); // 1 lan revoke cu + 1 lan issue moi
    }

    @Test
    void rotate_nemInvalidRefreshToken_khiTokenKhongTonTaiTrongDb() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotate("token-khong-ton-tai"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotate_nemInvalidRefreshToken_khiTokenDaHetHan() {
        RefreshToken expired = RefreshToken.builder()
                .userId(USER_ID)
                .tokenHash("hash-bat-ky")
                .revoked(false)
                .expiresAt(LocalDateTime.now().minusDays(1)) // da het han hom qua
                .build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> refreshTokenService.rotate("token-het-han"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("het han");
    }

    // ---------- REUSE DETECTION - phan quan trong nhat, test ky ----------

    @Test
    void rotate_phatHienReuse_khiTokenDaBiRevokedTruocDo_thuHoiToanBoTokenCuaUser() {
        // Mo phong tinh huong: token nay DA TUNG duoc rotate 1 lan roi (revoked=true),
        // nhung van co ai do (ke tan cong dang giu ban sao cu, hoac chinh chu vo tinh
        // dung lai tab/thiet bi cu) gui len lai request refresh voi token nay.
        RefreshToken alreadyUsedToken = validToken(USER_ID, true); // revoked = true

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(alreadyUsedToken));

        assertThatThrownBy(() -> refreshTokenService.rotate("token-da-bi-danh-cap"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("thu hoi");

        // HANH VI QUAN TRONG NHAT CAN TEST: phai goi revokeAllByUserId() de thu hoi
        // TOAN BO refresh token cua user nay, KHONG chi rieng token dang xet -
        // day chinh la co che bao ve khi phat hien dau hieu token bi lo.
        verify(refreshTokenRepository).revokeAllByUserId(USER_ID);

        // KHONG duoc phat hanh token moi nao trong tinh huong nay (tan cong bi chan)
        verify(jwtTokenProvider, never()).generateAccessToken(anyLong(), anyString());
    }

    @Test
    void rotate_nemInvalidRefreshToken_khiUserKhongConTonTai() {
        RefreshToken existing = validToken(USER_ID, false);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty()); // user da bi xoa

        assertThatThrownBy(() -> refreshTokenService.rotate("token-hop-le"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revoke_khongThrow_khiTokenKhongTonTai() {
        // Logout voi token da het han/khong ton tai van nen "thanh cong" tu goc
        // do UX (nguoi dung chi muon dang xuat) - KHONG duoc throw exception.
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> refreshTokenService.revoke("token-bat-ky"))
                .doesNotThrowAnyException();

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void revoke_danhDauRevoked_khiTokenTonTai() {
        RefreshToken existing = validToken(USER_ID, false);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));

        refreshTokenService.revoke("token-hop-le");

        assertThat(existing.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(existing);
    }

    // ---------- helpers ----------

    private RefreshToken validToken(Long userId, boolean revoked) {
        return RefreshToken.builder()
                .id(1L)
                .userId(userId)
                .tokenHash("hash-gia-lap")
                .revoked(revoked)
                .expiresAt(LocalDateTime.now().plusDays(29))
                .build();
    }

    private User userWith(Long id, String email) {
        User u = User.builder().googleId("google-" + id).email(email).build();
        u.setId(id);
        return u;
    }
}
