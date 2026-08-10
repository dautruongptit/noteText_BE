package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HomeResponse {
    // Thông tin user hiển thị trên header
    private String userName;
    private String avatarUrl;
    // Các card sự kiện sắp tới (tối đa 5)
    private java.util.List<EventResponse> upcomingEvents;
    // Danh sách người thân kèm sự kiện gần nhất
    private java.util.List<RelativeResponse> relatives;
    // Tab: sự kiện của bản thân
    private java.util.List<EventResponse> myEvents;
    private Boolean googleCalendarConnected;
}
