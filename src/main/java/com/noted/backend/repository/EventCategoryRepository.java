package com.noted.backend.repository;

import com.app.nhacsu.model.entity.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventCategoryRepository extends JpaRepository<EventCategory, Long> {

    Optional<EventCategory> findByCode(String code);

    /** Danh mục hệ thống + danh mục riêng của user (nếu sau này mở tính năng tự tạo) */
    List<EventCategory> findByIsSystemTrueOrUserIdOrderBySortOrderAsc(Long userId);

    List<EventCategory> findByIsSystemTrueOrderBySortOrderAsc();
}
