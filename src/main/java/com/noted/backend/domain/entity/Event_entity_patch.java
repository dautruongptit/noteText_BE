package com.noted.backend.domain.entity;

// ============================================================
// Event.java — THAY THẾ field eventType bằng category (SEC: Danh mục)
// (giữ nguyên toàn bộ field khác: title, eventDate, relative, reminders...)
// ============================================================

// 1. XÓA field cũ:
//    @Enumerated(EnumType.STRING)
//    private EventType eventType;

// 2. THÊM field mới:
//
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "category_id", nullable = false)
//    private EventCategory category;

// 3. Inner enum EventType — CÓ THỂ XÓA HẲN nếu không còn dùng ở đâu khác,
//    hoặc giữ lại tạm thời để tránh lỗi biên dịch ở chỗ khác chưa kịp sửa.
//    Khuyến nghị: xóa hẳn sau khi đã sửa xong toàn bộ Service/Controller.

// 4. RecurrenceType enum — GIỮ NGUYÊN, không liên quan đến thay đổi này.
