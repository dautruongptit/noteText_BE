package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
public class LoginHistoryResponse {
    private Long          id;
    private String        ipAddress;
    private String        deviceType;   // Mobile | Desktop | Tablet
    private String        os;           // Windows | macOS | Android | iOS
    private String        browser;      // Chrome | Safari | Firefox | Edge
    private String        country;
    private Boolean       isSuccess;
    private String        failureReason;
    private LocalDateTime loginAt;
}
