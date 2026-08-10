package com.noted.backend.controller;

import com.app.nhacsu.model.dto.response.BaseResponse;
import com.app.nhacsu.model.dto.response.EventCategoryResponse;
import com.app.nhacsu.repository.EventCategoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/event-categories")
@RequiredArgsConstructor
@Tag(name = "Event Categories", description = "Danh mục sự kiện — icon, màu quản lý từ DB")
public class EventCategoryController {

    private final EventCategoryRepository categoryRepo;

    /**
     * GET /event-categories
     * Flutter goi API nay LUC APP KHOI DONG (hoac cache local),
     * KHONG hardcode icon/mau trong code Flutter — moi thay doi
     * tu Backend se tu dong phan anh ma khong can release app moi.
     */
    @GetMapping
    @Cacheable(value = "eventCategories", key = "'system'")
    @Operation(summary = "Danh sách danh mục sự kiện (icon + màu)",
               description = "Trả về toàn bộ danh mục hệ thống, sắp xếp theo sort_order. Có cache Redis 30 phút.")
    public ResponseEntity<BaseResponse<?>> getCategories(
            @AuthenticationPrincipal Long userId) {

        List<EventCategoryResponse> categories = categoryRepo
            .findByIsSystemTrueOrderBySortOrderAsc()
            .stream()
            .map(EventCategoryResponse::from)
            .toList();

        return ResponseEntity.ok(BaseResponse.success(categories));
    }
}
