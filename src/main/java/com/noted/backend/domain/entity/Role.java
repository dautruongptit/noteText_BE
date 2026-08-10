package com.noted.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "roles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Ten role theo chuan Spring Security: phai bat dau bang "ROLE_"
     * VD: ROLE_USER, ROLE_ADMIN
     */
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /**
     * Enum tien ich de tranh hardcode chuoi "ROLE_USER" trong code.
     * Dung: Role.RoleName.ROLE_ADMIN.name()
     */
    public enum RoleName {
        ROLE_USER,
        ROLE_ADMIN
    }
}
