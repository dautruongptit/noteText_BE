package com.noted.backend.util;

/**
 * Token bucket don gian, thread-safe (synchronized method), luu TRONG BO NHO
 * (khong dung Redis).
 *
 * Phu hop voi quy mo trien khai HIEN TAI cua du an (1 instance backend duy
 * nhat chay tren Ubuntu server ca nhan - xem README backend). Neu sau nay
 * scale ra NHIEU instance backend (dat sau load balancer), PHAI chuyen sang
 * bo dem tap trung (VD Redis INCR + EXPIRE, hoac Redis Lua script token
 * bucket) - vi luc do moi instance se co danh sach TokenBucket RIENG, khong
 * dem chung duoc tong so request that su cua 1 user tren toan he thong.
 */
public class TokenBucket {

    private final long capacity;
    private final long refillIntervalMs;
    private long tokens;
    private long lastRefillTimestamp;

    public TokenBucket(long capacity, long refillIntervalMs) {
        this.capacity = capacity;
        this.refillIntervalMs = refillIntervalMs;
        this.tokens = capacity; // bat dau day binh, cho phep burst ngay tu dau
        this.lastRefillTimestamp = System.currentTimeMillis();
    }

    /** Tra ve true neu con token de "tieu thu" (request duoc phep di tiep), false neu het (nen 429). */
    public synchronized boolean tryConsume() {
        refill();
        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTimestamp;
        if (elapsed < refillIntervalMs) return;

        long tokensToAdd = elapsed / refillIntervalMs;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        // Cong don theo boi so refillIntervalMs (khong gan thang = now) de
        // khong "lam tron mat" phan thoi gian le con du, tranh nguoi dung bi
        // thiet 1 chut token moi lan refill do lam tron.
        lastRefillTimestamp += tokensToAdd * refillIntervalMs;
    }
}
