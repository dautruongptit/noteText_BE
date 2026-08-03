package com.noted.backend.service;

public interface RefreshTokenService {

    /** Sinh 1 refresh token moi cho user, luu hash vao DB, tra ve token GOC (chi lan nay thoi) */
    String issue(Long userId);

    /**
     * Xoay vong (rotate) refresh token: validate token cu, thu hoi no, phat hanh
     * cap token moi (access + refresh). Neu token cu khong hop le/het han/da bi
     * thu hoi truoc do -> throw InvalidRefreshTokenException.
     *
     * "Reuse detection": neu token dua vao DA TUNG bi revoke truoc do (tuc la ai
     * do dang dung lai 1 token cu da bi thay the) -> day la dau hieu manh cho thay
     * token da bi lo (bi danh cap va ke tan cong dang dung song song voi chu that).
     * Trong truong hop nay, THU HOI TOAN BO refresh token cua user do ngay lap tuc,
     * buoc phai dang nhap lai tren moi thiet bi.
     */
    RotateResult rotate(String rawRefreshToken);

    void revoke(String rawRefreshToken);

    void revokeAllForUser(Long userId);

    record RotateResult(Long userId, String email, String newAccessToken, String newRefreshToken, long accessTokenExpiresInMs) {}
}
