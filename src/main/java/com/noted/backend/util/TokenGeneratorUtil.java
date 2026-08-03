package com.noted.backend.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Sinh chuoi random dung lam opaque refresh token.
 * Dung SecureRandom (KHONG dung Math.random hay Random thuong - khong du an toan
 * cho muc dich bao mat, co the bi du doan duoc).
 */
public final class TokenGeneratorUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bit

    private TokenGeneratorUtil() {}

    public static String generateOpaqueToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
