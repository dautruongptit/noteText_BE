package com.noted.backend.domain.entity;

// ============================================================
// EventService.java — CẬP NHẬT 3 method: create(), update(), toResponse()
// (giữ nguyên toàn bộ logic cũ về reminders, cache eviction...)
// ============================================================

// 1. Thêm dependency mới (constructor injection):
// private final EventParticipantRepository participantRepo;
// private final RelativeRepository relativeRepo;  // đã có sẵn

// 2. Trong method create() — SAU đoạn xử lý reminders, THÊM:
//
//    if (req.getParticipantIds() != null && !req.getParticipantIds().isEmpty()) {
//        saveParticipants(event, userId, req.getParticipantIds());
//    }

// 3. Trong method update() — SAU đoạn clear() reminders cũ, THÊM:
//
//    // Xoa participants cu, tao lai tu danh sach moi (giong logic reminders)
//    participantRepo.deleteByEventId(event.getId());
//    if (req.getParticipantIds() != null && !req.getParticipantIds().isEmpty()) {
//        saveParticipants(event, userId, req.getParticipantIds());
//    }

// 4. Thêm helper method mới:
//
//    private void saveParticipants(Event event, Long userId, List<Long> relativeIds) {
//        List<Relative> relatives = relativeRepo.findAllById(relativeIds).stream()
//            .filter(r -> r.getUser().getId().equals(userId))  // chi cho phep relative cua chinh user
//            .toList();
//
//        List<EventParticipant> participants = relatives.stream()
//            .map(r -> EventParticipant.builder()
//                .event(event)
//                .relative(r)
//                .build())
//            .toList();
//
//        participantRepo.saveAll(participants);
//    }

// 5. Trong method toResponse() — THÊM map participants vào response:
//
//    public EventResponse toResponse(Event event, LocalDate today) {
//        EventResponse response = EventResponse.from(event, today);
//
//        List<EventParticipant> participants = participantRepo.findByEventId(event.getId());
//        if (!participants.isEmpty()) {
//            response.setParticipants(participants.stream()
//                .map(ep -> EventResponse.ParticipantSummary.builder()
//                    .id(ep.getRelative().getId())
//                    .name(ep.getRelative().getName())
//                    .avatarUrl(ep.getRelative().getAvatarUrl())
//                    .build())
//                .toList());
//        }
//        return response;
//    }

// 6. Trong method delete() — KHÔNG cần sửa gì thêm.
//    Vì FK event_participants.event_id có ON DELETE CASCADE,
//    nhưng delete() hiện tại là SOFT DELETE (isActive=false) nên
//    participants VẪN GIỮ NGUYÊN trong DB — đúng ý muốn (event có thể
//    khôi phục sau này nếu cần, không mất participants).
