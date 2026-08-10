package com.noted.backend.controller;

// ============================================================
// EventController.java — ĐỔI query param ?type= → ?categoryId=
// ============================================================

// GET /events — XÓA:
//    @RequestParam(required = false) String type,
//
// THAY BẰNG:
//    @RequestParam(required = false) Long categoryId,
//
// Và trong body method, gọi:
//    eventService.getEvents(userId, categoryId, relativeId, month, year)
//
// (tham số truyền xuống Service cũng đổi theo — xem EventService_category_patch.java)

// ⚠ Đây là BREAKING CHANGE cho API — Flutter phải đổi
//    GET /events?type=SINH_NHAT
//    thành
//    GET /events?categoryId=1
//
//    Flutter lấy categoryId từ GET /event-categories trước
//    (gọi 1 lần lúc app khởi động, cache local).
