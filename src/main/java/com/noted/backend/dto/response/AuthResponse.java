package com.noted.backend.dto.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Trả về sau khi đăng ký / đăng nhập / refresh token thành công.
 * Chứa thông tin cơ bản của user và cặp JWT (access + refresh).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /** ID người dùng trong hệ thống. */
    private Long    id;

    /** Họ và tên đầy đủ. */
    private String  fullName;

    /** Email đăng nhập. */
    private String  email;

    /**
     * Access Token (JWT) — thời hạn 24 giờ.
     * Client gửi kèm mọi request trong header:
     * Authorization: Bearer <accessToken>
     */
    private String  accessToken;

    /**
     * Refresh Token — thời hạn 7 ngày.
     * Dùng để lấy accessToken mới khi hết hạn.
     * POST /api/v1/auth/refresh  { "refreshToken": "..." }
     */
    private String  refreshToken;

}
