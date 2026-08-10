package com.noted.backend.dto.response;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder
public class UserProfileResponse {
    private Long id;
    private String fullName;
    private String email;
    private String avatarUrl;
    private String language;
    private Boolean darkMode;
    private Integer totalEvents;
    private Integer totalRelatives;
    private Integer daysUntilNextEvent; // tính từ DB
    private Boolean googleCalendarConnected;
    private LocalDateTime createdAt;
    private Integer       failedLoginCount;  // So lan sai hien tai
    private LocalDateTime lastLoginAt;       // Dang nhap thanh cong gan nhat
    private LocalDateTime lastFailedAt;      // Dang nhap sai gan nhat
    private Integer       totalLoginCount;   // Tong lan dang nhap thanh cong
    private Boolean       isLocked;          // Tai khoan dang bi khoa?
    private LocalDateTime lockedUntil;       // Khoa den khi nao (null = khong khoa)
    private String        lastLoginIp;       // IP lan dang nhap cuoi

}
