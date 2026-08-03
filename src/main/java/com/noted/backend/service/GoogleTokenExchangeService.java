package com.noted.backend.service;

public interface GoogleTokenExchangeService {

    /** Doi authorization "code" (Google redirect ve /api/drive/callback) lay access + refresh token */
    TokenResult exchangeCode(String code);

    record TokenResult(String accessToken, String refreshToken, long expiresInSeconds) {}
}
