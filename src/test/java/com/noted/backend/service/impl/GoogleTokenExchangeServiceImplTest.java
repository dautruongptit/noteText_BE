package com.noted.backend.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.noted.backend.exception.DriveTokenExchangeException;
import com.noted.backend.service.GoogleTokenExchangeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * KHONG goi mang that ra Google (se cham, khong on dinh trong CI, va can
 * client_id/secret that). Thay vao do, tao 1 subclass override method
 * "protected requestToken()" (da tach rieng trong GoogleTokenExchangeServiceImpl,
 * xem SEC-11) de tra ve GoogleTokenResponse gia lap - nho do test duoc TOAN BO
 * logic xu ly ket qua (map sang TokenResult, xu ly refresh_token=null, bat
 * exception) ma khong phu thuoc mang/credential that.
 */
class GoogleTokenExchangeServiceImplTest {

    private GoogleTokenExchangeServiceImpl newServiceReturning(GoogleTokenResponse fakeResponse) {
        GoogleTokenExchangeServiceImpl service = new GoogleTokenExchangeServiceImpl() {
            @Override
            protected GoogleTokenResponse requestToken(String code) {
                return fakeResponse;
            }
        };
        ReflectionTestUtils.setField(service, "clientId", "fake-client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "fake-client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8084/api/drive/callback");
        return service;
    }

    private GoogleTokenExchangeServiceImpl newServiceThrowing(Exception toThrow) {
        GoogleTokenExchangeServiceImpl service = new GoogleTokenExchangeServiceImpl() {
            @Override
            protected GoogleTokenResponse requestToken(String code) throws Exception {
                throw toThrow;
            }
        };
        ReflectionTestUtils.setField(service, "clientId", "fake-client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "fake-client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8084/api/drive/callback");
        return service;
    }

    @Test
    void exchangeCode_traVeTokenResultDung_khiGoogleTraVeDuCaAccessVaRefreshToken() {
        GoogleTokenResponse fake = new GoogleTokenResponse();
        fake.setAccessToken("ya29.fake-access-token");
        fake.setRefreshToken("1//fake-refresh-token");
        fake.setExpiresInSeconds(3599L);

        GoogleTokenExchangeService.TokenResult result = newServiceReturning(fake).exchangeCode("auth-code-tu-google");

        assertThat(result.accessToken()).isEqualTo("ya29.fake-access-token");
        assertThat(result.refreshToken()).isEqualTo("1//fake-refresh-token");
        assertThat(result.expiresInSeconds()).isEqualTo(3599L);
    }

    @Test
    void exchangeCode_dungMacDinh3600s_khiGoogleKhongTraExpiresIn() {
        GoogleTokenResponse fake = new GoogleTokenResponse();
        fake.setAccessToken("ya29.fake-access-token");
        fake.setRefreshToken("1//fake-refresh-token");
        // KHONG set expiresInSeconds - gia lap truong hop Google khong tra ve field nay

        var result = newServiceReturning(fake).exchangeCode("auth-code");

        assertThat(result.expiresInSeconds()).isEqualTo(3600L);
    }

    @Test
    void exchangeCode_nemDriveTokenExchangeException_khiGoogleKhongTraRefreshToken() {
        // Day la case QUAN TRONG NHAT: Google chi tra refresh_token o LAN DAU
        // nguoi dung consent - neu thieu (VD nguoi dung da tung consent truoc do
        // ma app chua kip luu), PHAI bao loi ro rang thay vi luu refresh_token=null
        // roi that bai am tham o lan dong bo Drive dau tien (rat kho debug).
        GoogleTokenResponse fake = new GoogleTokenResponse();
        fake.setAccessToken("ya29.fake-access-token");
        fake.setRefreshToken(null); // Google KHONG tra ve refresh_token

        assertThatThrownBy(() -> newServiceReturning(fake).exchangeCode("auth-code"))
                .isInstanceOf(DriveTokenExchangeException.class)
                .hasMessageContaining("refresh_token");
    }

    @Test
    void exchangeCode_nemDriveTokenExchangeException_khiGoiGoogleThatBai() {
        // Loi mang/Google tra ve loi (VD code het han, sai client_id...) - phai
        // duoc BOC LAI thanh DriveTokenExchangeException (khong de lo chi tiet
        // exception ky thuat ra ngoai API response cho FE).
        var service = newServiceThrowing(new java.io.IOException("invalid_grant"));

        assertThatThrownBy(() -> service.exchangeCode("auth-code-het-han"))
                .isInstanceOf(DriveTokenExchangeException.class);
    }

    @Test
    void exchangeCode_khongBocLaiHaiLan_khiDaLaDriveTokenExchangeException() {
        // Neu requestToken() (hiem gap) da nem san DriveTokenExchangeException,
        // khong duoc boc no vao trong 1 DriveTokenExchangeException khac (mat
        // message goc) - catch block dau tien trong exchangeCode() phai re-throw nguyen ven.
        var service = newServiceThrowing(new DriveTokenExchangeException("loi cu the tu ben trong"));

        assertThatThrownBy(() -> service.exchangeCode("auth-code"))
                .isInstanceOf(DriveTokenExchangeException.class)
                .hasMessage("loi cu the tu ben trong");
    }
}
