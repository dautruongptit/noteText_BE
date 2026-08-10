package com.noted.backend.dto.response;

// ============================================================
// EventService.java — CẬP NHẬT các chỗ dùng eventType → categoryId
// (giữ nguyên toàn bộ logic reminders, participants, cache eviction...)
// ============================================================

// 1. Thêm dependency mới:
// private final EventCategoryRepository categoryRepo;

// 2. Trong create() — XÓA dòng cũ:
//    .eventType(Event.EventType.valueOf(req.getEventType()))
//
//    THAY BẰNG (resolve category trước khi build Event):
//    EventCategory category = categoryRepo.findById(req.getCategoryId())
//        .orElseThrow(() -> new ResourceNotFoundException("EventCategory", req.getCategoryId()));
//
//    Event event = Event.builder()
//        .title(req.getTitle())
//        .category(category)          // ← thay cho .eventType(...)
//        .eventDate(req.getEventDate())
//        ...

// 3. Trong update() — tương tự, XÓA dòng eventType cũ, THAY BẰNG:
//    EventCategory category = categoryRepo.findById(req.getCategoryId())
//        .orElseThrow(() -> new ResourceNotFoundException("EventCategory", req.getCategoryId()));
//    event.setCategory(category);

// 4. Trong getEvents() — filter theo categoryId thay vì parse enum:
//
//    XÓA:
//    Event.EventType type = typeStr != null ? Event.EventType.valueOf(typeStr) : null;
//    return eventRepo.findFiltered(userId, type, relativeId, month, year)...
//
//    THAY BẰNG (đổi luôn tham số method từ String typeStr sang Long categoryId):
//    public List<EventResponse> getEvents(Long userId, Long categoryId,
//                                         Long relativeId, Integer month, Integer year) {
//        return eventRepo.findFiltered(userId, categoryId, relativeId, month, year)
//            .stream().map(e -> toResponse(e, LocalDate.now())).toList();
//    }
//
//    ⚠ Cache key cũng cần đổi theo (xem mục 6)

// 5. Trong toResponse() — dùng EventCategoryResponse.from() thay vì string:
//    (đã ghi chú ở EventResponse_category_patch.java)

// 6. Cache key trong @Cacheable — ĐỔI vì tham số method đã đổi kiểu:
//
//    @Cacheable(value = "events",
//        key = "#userId + '::' + (#categoryId ?: 0) + '::' + (#relativeId ?: 0)"
//              + " + '::' + (#month ?: 0) + '::' + (#year ?: 0)")
//    public List<EventResponse> getEvents(Long userId, Long categoryId, ...)
