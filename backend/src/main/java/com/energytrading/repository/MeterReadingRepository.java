package com.energytrading.repository;

import com.energytrading.model.MeterReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MeterReadingRepository extends JpaRepository<MeterReading, Long> {
    Optional<MeterReading> findByReadingId(String readingId);
    boolean existsByReadingId(String readingId);
    List<MeterReading> findByHouseholdIdOrderBySequenceNoDesc(Long householdId);
    Optional<MeterReading> findTopByHouseholdIdOrderBySequenceNoDesc(Long householdId);
}
