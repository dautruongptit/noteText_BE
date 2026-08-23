package com.noted.backend.service.impl;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.drive.Drive;
import com.noted.backend.exception.GoogleDriveOperationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

/**
 * Bug thuc te 2026-08-22: pull mot note RONG (0 byte, VD "New Note (2).txt"
 * vua tao) tu Drive ve bi loi "416 Requested range not satisfiable".
 *
 * Nguyen nhan goc (xem MediaHttpDownloader.executeCurrentRequest trong
 * google-api-client): drive.files().get(fileId).executeMediaAndDownloadTo()
 * MAC DINH tai theo kieu "resumable/chunked" - LUON gan header
 * "Range: bytes=0-33554431" (chunkSize mac dinh 32MB) ngay ca cho request
 * DAU TIEN. Voi file 0 byte, KHONG co byte nao thoa man range do -> Drive
 * tra 416 thay vi 200 rong.
 *
 * Test nay gia lap DUNG hanh vi that cua Drive (416 khi thay header Range,
 * 200 rong khi khong co) bang MockHttpTransport - KHONG goi mang that.
 */
class GoogleDriveServiceImplDownloadTest {

    /** MockHttpTransport gia lap 1 file Drive co dung 0 byte noi dung. */
    private MockHttpTransport transportForEmptyFile() {
        return new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public com.google.api.client.http.LowLevelHttpResponse execute() throws IOException {
                        MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                        if (getFirstHeaderValue("Range") != null) {
                            // Dung HANH VI THAT cua Drive: file 0 byte + co Range -> 416
                            response.setStatusCode(416);
                            response.setContent("{\"error\":{\"code\":416,\"message\":\"Request range not satisfiable\"}}");
                        } else {
                            // Khong co Range (direct download) -> Drive tra 200 rong binh thuong
                            response.setStatusCode(200);
                            response.setContent("");
                            response.setContentType("text/plain");
                        }
                        return response;
                    }
                };
            }
        };
    }

    private Drive buildDrive(MockHttpTransport transport) {
        HttpRequestInitializer noopInitializer = request -> { };
        return new Drive.Builder(transport, GsonFactory.getDefaultInstance(), noopInitializer)
                .setApplicationName("noted-backend-test")
                .build();
    }

    @Test
    void downloadFileContent_traVeChuoiRong_khiFileDriveRong0Byte() {
        Drive drive = buildDrive(transportForEmptyFile());
        GoogleDriveServiceImpl service = new GoogleDriveServiceImpl(null);

        String content = service.downloadFileContent(drive, "empty-file-id");

        assertThat(content).isEmpty();
    }

    @Test
    void downloadFileContent_nemGoogleDriveOperationException_khiThatBaiThatSu() {
        // Dam bao fix khong "nuot" loi that (VD 404/403 that) - chi xu ly rieng
        // truong hop 416 do resumable-download gay ra tren file rong.
        MockHttpTransport transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public com.google.api.client.http.LowLevelHttpResponse execute() throws IOException {
                        MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                        response.setStatusCode(404);
                        response.setContent("{\"error\":{\"code\":404,\"message\":\"File not found\"}}");
                        return response;
                    }
                };
            }
        };
        Drive drive = buildDrive(transport);
        GoogleDriveServiceImpl service = new GoogleDriveServiceImpl(null);

        assertThatThrownBy(() -> service.downloadFileContent(drive, "missing-file-id"))
                .isInstanceOf(GoogleDriveOperationException.class);
    }
}
