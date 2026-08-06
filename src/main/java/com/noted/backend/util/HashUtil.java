package com.noted.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {

    private HashUtil() {}

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 khong kha dung", e);
        }
    }

    /**
     * MD5 - CHI dung de so sanh voi truong "md5Checksum" ma chinh Google Drive
     * tra ve cho moi file (xem GoogleDriveService.getFileChecksum) - KHONG
     * dung MD5 cho muc dich bao mat nao khac trong he thong (da dung SHA-256
     * cho content_hash o tren, va MD5 KHONG duoc coi la an toan mat ma hoc,
     * chi phu hop cho muc dich SO SANH NOI DUNG don thuan nhu o day).
     */
    public static String md5(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 khong kha dung", e);
        }
    }
}
