package com.noted.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GroupSummaryResponse {
    private String groupType;    // GIA_DINH, VO_CHONG...
    private String displayName;  // "Gia đình", "Vợ/Chồng"...
    private Integer count;       // số người trong nhóm
}

