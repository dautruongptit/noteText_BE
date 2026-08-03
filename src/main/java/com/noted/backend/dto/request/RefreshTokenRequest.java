package com.noted.backend.dto.request;

/**
 * Fallback cho truong hop client KHONG the dung httpOnly cookie (VD goi API
 * truc tiep tu mobile app hoac Postman/CLI). Voi web frontend chuan, refresh
 * token nen luon di qua cookie (xem AuthController.refresh()), truong nay
 * co the null khi request den tu cookie.
 */
public record RefreshTokenRequest(
        String refreshToken
) {}
