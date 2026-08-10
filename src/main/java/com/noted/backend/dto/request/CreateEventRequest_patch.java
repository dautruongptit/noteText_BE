package com.noted.backend.dto.request;

// ============================================================
// CreateEventRequest.java — THÊM field mới vào class hiện tại
// (giữ nguyên toàn bộ field cũ: title, eventType, eventDate...)
// ============================================================

// Thêm field mới:
//
//    /**
//     * Danh sach ID nguoi than cung lien quan (tuy chon).
//     * Dung cho su kien nhieu nguoi: VD Ky niem ngay cuoi = [idVo].
//     * Neu chi co 1 nguoi, van nen dung relativeId nhu cu — field nay
//     * CHI dung khi that su can gan NHIEU hon 1 nguoi.
//     */
//    private List<Long> participantIds;   // co the null hoac rong
