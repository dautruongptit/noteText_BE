package com.noted.backend.domain.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Entity @Table(name = "relatives")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Relative {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", nullable = false)
    private GroupType groupType;

    public enum GroupType {
        GIA_DINH, VO_CHONG, CON_CAI, BAN_BE
    }

    @Enumerated(EnumType.STRING)
    private Gender gender;
    public enum Gender { MALE, FEMALE, OTHER }

    @Column(name = "date_of_birth")
    private java.time.LocalDate dateOfBirth;

    @Column(length = 200)
    private String location;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private java.math.BigDecimal heightCm;

    @Column(name = "weight_kg", precision = 5, scale = 1)
    private java.math.BigDecimal weightKg;

    @Column(columnDefinition = "TEXT")
    private String hobbies; // JSON array string

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "total_events")
    private Integer totalEvents = 0;

    @OneToMany(mappedBy = "relative", cascade = CascadeType.ALL)
    private java.util.List<Event> events;
}

