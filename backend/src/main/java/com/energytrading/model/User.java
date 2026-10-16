package com.energytrading.model;

import com.energytrading.model.enums.Role;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_household_code", columnList = "householdCode", unique = true),
    @Index(name = "idx_user_email", columnList = "email", unique = true)
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 50)
    private String householdCode;

    @NotBlank
    @Column(nullable = false, length = 100)
    private String displayAlias;

    @Column(length = 150)
    private String fullName;

    @Email
    @Column(unique = true, length = 150)
    private String email;

    @NotBlank
    @Column(nullable = false, length = 255)
    private String passwordHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Role role;

    @NotNull
    @PositiveOrZero
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @NotNull
    @PositiveOrZero
    @Column(nullable = false)
    private Double currentSurplusKwh = 0.0;

    @NotNull
    @PositiveOrZero
    @Column(nullable = false)
    private Double currentDeficitKwh = 0.0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public User() {
    }

    public User(String householdCode, String displayAlias, String fullName, String email,
                String passwordHash, Role role, BigDecimal balance,
                Double currentSurplusKwh, Double currentDeficitKwh) {
        this.householdCode = householdCode;
        this.displayAlias = displayAlias;
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.balance = balance != null ? balance : BigDecimal.ZERO;
        this.currentSurplusKwh = currentSurplusKwh != null ? currentSurplusKwh : 0.0;
        this.currentDeficitKwh = currentDeficitKwh != null ? currentDeficitKwh : 0.0;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHouseholdCode() {
        return householdCode;
    }

    public void setHouseholdCode(String householdCode) {
        this.householdCode = householdCode;
    }

    public String getDisplayAlias() {
        return displayAlias;
    }

    public void setDisplayAlias(String displayAlias) {
        this.displayAlias = displayAlias;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Double getCurrentSurplusKwh() {
        return currentSurplusKwh;
    }

    public void setCurrentSurplusKwh(Double currentSurplusKwh) {
        this.currentSurplusKwh = currentSurplusKwh;
    }

    public Double getCurrentDeficitKwh() {
        return currentDeficitKwh;
    }

    public void setCurrentDeficitKwh(Double currentDeficitKwh) {
        this.currentDeficitKwh = currentDeficitKwh;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
