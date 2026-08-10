package com.noted.backend.domain.entity;

// ============================================================
// CreateEventRequest.java — ĐỔI field eventType (String) → categoryId (Long)
// ============================================================

// XÓA:
//    @NotBlank
//    private String eventType;

// THAY BẰNG:
//    @NotNull(message = "categoryId khong duoc de trong")
//    private Long categoryId;

// Field participantIds (đã thêm ở phần event_participants) giữ nguyên,
// không liên quan đến thay đổi này.
