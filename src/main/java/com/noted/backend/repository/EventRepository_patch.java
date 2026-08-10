package com.noted.backend.repository;

// ============================================================
// EventRepository.java — ĐỔI method findFiltered() tham số
// eventType (Enum) → categoryId (Long)
// ============================================================

// XÓA method cũ:
//    @Query("SELECT e FROM Event e WHERE e.user.id = :userId"
//         + " AND (:type IS NULL OR e.eventType = :type)"
//         + " AND (:relativeId IS NULL OR e.relative.id = :relativeId)"
//         + " AND (:month IS NULL OR MONTH(e.eventDate) = :month)"
//         + " AND (:year IS NULL OR YEAR(e.eventDate) = :year)"
//         + " AND e.isActive = true ORDER BY e.eventDate ASC")
//    List<Event> findFiltered(Long userId, Event.EventType type,
//                              Long relativeId, Integer month, Integer year);

// THAY BẰNG:
//    @Query("SELECT e FROM Event e WHERE e.user.id = :userId"
//         + " AND (:categoryId IS NULL OR e.category.id = :categoryId)"
//         + " AND (:relativeId IS NULL OR e.relative.id = :relativeId)"
//         + " AND (:month IS NULL OR MONTH(e.eventDate) = :month)"
//         + " AND (:year IS NULL OR YEAR(e.eventDate) = :year)"
//         + " AND e.isActive = true ORDER BY e.eventDate ASC")
//    List<Event> findFiltered(@Param("userId") Long userId,
//                              @Param("categoryId") Long categoryId,
//                              @Param("relativeId") Long relativeId,
//                              @Param("month") Integer month,
//                              @Param("year") Integer year);

// Các method khác (findUpcoming, findMyUpcoming, findByIdAndUserId...)
// KHÔNG cần đổi — không liên quan đến eventType.
