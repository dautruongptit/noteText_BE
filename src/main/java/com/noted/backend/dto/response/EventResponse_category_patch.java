package com.noted.backend.dto.response;

// ============================================================
// EventResponse.java — ĐỔI field eventType (String) → category (object)
// ============================================================

// XÓA:
//    private String eventType;

// THAY BẰNG:
//    private EventCategoryResponse category;
//    // Tra ve nguyen object { id, code, displayName, icon, colorHex }
//    // thay vi chi 1 string — Flutter khong can tu map code sang
//    // icon/mau nua, backend da tra san.

// Trong method from(Event event, LocalDate today):
//
//    .category(EventCategoryResponse.from(event.getCategory()))

// Field participants (đã thêm ở phần event_participants) giữ nguyên.
