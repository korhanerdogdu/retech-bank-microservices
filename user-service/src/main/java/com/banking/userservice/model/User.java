package com.banking.userservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String fullName;

    // Stored lower-case; used to log in
    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    // BCrypt hash — the password itself is never stored
    @Column(nullable = false)
    private String passwordHash;

    // The account opened at registration (Account Service)
    private String accountNumber;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
