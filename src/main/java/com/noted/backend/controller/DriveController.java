package com.noted.backend.controller;

import com.noted.backend.domain.entity.User;
import com.noted.backend.exception.DriveTokenExchangeException;
import com.noted.backend.repository.UserRepository;
import com.noted.backend.security.JwtTokenProvider;
import com.noted.backend.service.DriveSyncService;
import com.noted.backend.service.GoogleTokenExchangeService;
import com.noted.backend.util.CryptoUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * Luong xin quyen Drive (offline access + refresh_token) - HOAN THIEN o SEC-09
 * (truoc do chi la khung, xem ghi chu lich su o cac SEC-01/03/07):
 *
 *  1) FE goi GET /api/drive/connect (co JWT, biet duoc userId) -> nhan ve authUrl
 *     THAT (client_id/redirect_uri da duoc dien gia tri that, kem "state" da
 *     ky de chong CSRF - xem JwtTokenProvider.generateDriveStateToken).
 *  2) FE dieu huong trinh duyet (window.location.href = authUrl) sang man hinh
 *     consent cua Google.
 *  3) Google redirect trinh duyet VE GET /api/drive/callback kem "code" + "state"
 *     - request nay KHONG co header Authorization (day la dieu huong trinh
 *     duyet thuan tuy, khong phai fetch/XHR) nen PHAI permitAll o SecurityConfig,
 *     danh dan hoan toan vao "state" (da ky JWT) de biet la user nao.
 *  4) callback() doi "code" lay refresh_token that (GoogleTokenExchangeService),
 *     ma hoa AES-256-GCM (CryptoUtil) roi luu vao users.drive_refresh_token_enc,
 *     tao/tim app folder ngay (phat hien loi som), roi redirect trinh duyet VE
 *     FRONTEND kem ket qua connected=true/false.
 */
@RestController
@RequestMapping("/api/drive")
@RequiredArgsConstructor
@Slf4j
public class DriveController {

    private final DriveSyncService driveSyncService;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleTokenExchangeService googleTokenExchangeService;
    private final CryptoUtil cryptoUtil;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    @Value("${app.drive.app-folder-name}")
    private String appFolderName;

    @Value("${app.drive.oauth-scope}")
    private String driveOauthScope;

    @Value("${app.drive.redirect-uri}")
    private String driveRedirectUri;

    @Value("${app.drive.frontend-callback-url}")
    private String driveFrontendCallbackUrl;

    @GetMapping("/status")
    public Map<String, Object> status(@AuthenticationPrincipal Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        // Tra ca folderName de UI hien TEN THAT cua thu muc. Truoc day DrivePanel
        // viet cung chuoi "/Noted Workspace" trong khi ten that la "NotedApp" -
        // nguoi dung doc xong di tim trong Drive khong thay, tuong app khong tao
        // thu muc (bao cao thuc te 2026-08-22). Backend da TU TAO thu muc thi cang
        // phai cho nguoi dung nhin thay ket qua, neu khong no thanh hop den.
        return Map.of(
                "connected", user.isDriveConnected(),
                "folderId", user.getDriveFolderId() == null ? "" : user.getDriveFolderId(),
                "folderName", appFolderName
        );
    }

    /** FE redirect nguoi dung toi authUrl nay de bat dau luong xin quyen offline */
    @GetMapping("/connect")
    public Map<String, String> connect(@AuthenticationPrincipal Long userId) {
        String state = jwtTokenProvider.generateDriveStateToken(userId);

        String authUrl = UriComponentsBuilder
                .fromHttpUrl("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", googleClientId)
                .queryParam("redirect_uri", driveRedirectUri)
                .queryParam("response_type", "code")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent") // dam bao GAN NHU luon nhan duoc refresh_token (xem GoogleTokenExchangeServiceImpl)
                // Scope doc tu cau hinh (app.drive.oauth-scope), KHONG viet cung:
                // luong nay va luong dang nhap o application.yml PHAI luon dung
                // cung 1 gia tri, ma truoc day moi cho ghi rieng nen da tung lech
                // nhau. Xem giai thich day du ve 2 lua chon scope trong
                // application.yml canh "oauth-scope".
                .queryParam("scope", driveOauthScope)
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();

        return Map.of("authUrl", authUrl);
    }

    /**
     * Google redirect trinh duyet ve day sau khi nguoi dung dong y/tu choi consent.
     * KHONG duoc bao ve boi JWT filter (xem SecurityConfig - permitAll rieng cho
     * GET /api/drive/callback), vi day la dieu huong trinh duyet thuan tuy.
     */
    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                          @RequestParam(required = false) String state,
                          @RequestParam(required = false) String error,
                          HttpServletResponse response) throws IOException {

        if (error != null) {
            log.info("Nguoi dung tu choi cap quyen Drive: {}", error);
            response.sendRedirect(driveFrontendCallbackUrl + "?connected=false&reason=" + error);
            return;
        }

        if (code == null || state == null) {
            response.sendRedirect(driveFrontendCallbackUrl + "?connected=false&reason=missing_params");
            return;
        }

        Long userId;
        try {
            userId = jwtTokenProvider.getUserIdFromDriveStateToken(state);
        } catch (IllegalArgumentException e) {
            // state khong hop le/het han/bi gia mao - xem giai thich CSRF o JwtTokenProvider
            log.warn("State token khong hop le o /api/drive/callback: {}", e.getMessage());
            response.sendRedirect(driveFrontendCallbackUrl + "?connected=false&reason=invalid_state");
            return;
        }

        try {
            var tokenResult = googleTokenExchangeService.exchangeCode(code);

            User user = userRepository.findById(userId).orElseThrow();
            user.setDriveRefreshTokenEnc(cryptoUtil.encrypt(tokenResult.refreshToken()));
            user.setDriveConnected(true);
            userRepository.save(user);

            // Tao/tim app folder NGAY - phat hien som neu co van de (VD quota,
            // scope khong du) thay vi de nguoi dung tuong da ket noi thanh cong
            // roi lan sync dau tien moi bao loi.
            driveSyncService.ensureAppFolder(userId);

            response.sendRedirect(driveFrontendCallbackUrl + "?connected=true");
        } catch (DriveTokenExchangeException e) {
            log.warn("Doi token that bai cho userId={}: {}", userId, e.getMessage());
            response.sendRedirect(driveFrontendCallbackUrl + "?connected=false&reason=exchange_failed");
        } catch (Exception e) {
            log.error("Loi khong xac dinh khi hoan tat ket noi Drive cho userId={}", userId, e);
            response.sendRedirect(driveFrontendCallbackUrl + "?connected=false&reason=unknown_error");
        }
    }

    @PostMapping("/sync-all")
    public Map<String, String> syncAll(@AuthenticationPrincipal Long userId) {
        driveSyncService.ensureAppFolder(userId);
        // PUSH truoc: "Flush" thu cong - day NGAY toan bo note dirty cua RIENG
        // user nay, bo qua nguong debounce 30s (xem DriveSyncServiceImpl,
        // "Debounce Sync"). Dung chung cho ca nut "Dong bo ngay" TREN UI lan
        // luc trinh duyet flush khi dong tab (frontend goi CUNG endpoint nay
        // qua fetch(..., {keepalive:true})).
        driveSyncService.flushDirtyNotes(userId);
        // PULL sau: doi chieu nguoc lai voi Drive (file moi tao truc tiep tren
        // Drive/tu thiet bi khac, hoac Drive co thay doi noi dung). PUSH truoc
        // dam bao Drive da co ban local moi nhat truoc khi doi chieu, tranh
        // pull nham phai ban Drive cu (xem DriveSyncService.pullFromDrive javadoc).
        driveSyncService.pullFromDrive(userId);
        return Map.of("message", "sync_triggered");
    }

    @DeleteMapping("/disconnect")
    public Map<String, String> disconnect(@AuthenticationPrincipal Long userId) {
        driveSyncService.disconnect(userId);
        return Map.of("message", "disconnected");
    }
}
