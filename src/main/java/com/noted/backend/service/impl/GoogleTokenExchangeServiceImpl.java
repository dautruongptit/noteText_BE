package com.noted.backend.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.noted.backend.exception.DriveTokenExchangeException;
import com.noted.backend.service.GoogleTokenExchangeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hoan thien phan "còn treo" tu SEC-03/SEC-07: doi authorization code THAT lay
 * access_token + refresh_token tu Google, dung GoogleAuthorizationCodeTokenRequest
 * (co san trong dependency google-api-client, KHONG can them thu vien HTTP client
 * moi hay tu viet JSON parsing tay).
 */
@Service
@Slf4j
public class GoogleTokenExchangeServiceImpl implements GoogleTokenExchangeService {

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    @Value("${app.drive.redirect-uri}")
    private String redirectUri;

    @Override
    public TokenResult exchangeCode(String code) {
        try {
            GoogleTokenResponse tokenResponse = requestToken(code);

            String refreshToken = tokenResponse.getRefreshToken();
            if (refreshToken == null) {
                // Google CHI tra refresh_token o LAN DAU nguoi dung consent. Da dat
                // "prompt=consent" o DriveController.connect() de gan nhu luon dam bao
                // co, nhung neu van thieu (VD edge-case hiem gap), bao loi ro rang thay
                // vi luu refresh_token = null roi that bai am tham o lan sync dau tien.
                throw new DriveTokenExchangeException(
                        "Google khong tra ve refresh_token. Vui long thu hoi quyen truy cap cu tai " +
                        "https://myaccount.google.com/permissions roi ket noi lai.");
            }

            long expiresIn = tokenResponse.getExpiresInSeconds() != null
                    ? tokenResponse.getExpiresInSeconds()
                    : 3600L;

            return new TokenResult(tokenResponse.getAccessToken(), refreshToken, expiresIn);

        } catch (DriveTokenExchangeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Doi authorization code lay Google token that bai", e);
            throw new DriveTokenExchangeException("Khong the xac thuc voi Google, vui long thu lai");
        }
    }

    /**
     * Tach rieng phan GOI MANG THAT ra Google thanh 1 method "protected" (thay vi
     * inline het trong exchangeCode()) CHI DE PHUC VU UNIT TEST (xem SEC-11,
     * GoogleTokenExchangeServiceImplTest): test tao 1 subclass override method nay,
     * tra ve GoogleTokenResponse gia lap thay vi goi HTTP that ra Google, nho do
     * kiem tra duoc toan bo logic xu ly ket qua (null refresh_token, exception
     * mapping...) ma khong phu thuoc mang/credential that.
     */
    protected GoogleTokenResponse requestToken(String code) throws Exception {
        return new GoogleAuthorizationCodeTokenRequest(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                clientId,
                clientSecret,
                code,
                redirectUri)
                .execute();
    }
}
