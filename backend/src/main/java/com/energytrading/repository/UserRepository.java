package com.energytrading.repository;

import com.energytrading.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByHouseholdCode(String householdCode);
    Optional<User> findByEmail(String email);
    boolean existsByHouseholdCode(String householdCode);
    boolean existsByEmail(String email);
}
