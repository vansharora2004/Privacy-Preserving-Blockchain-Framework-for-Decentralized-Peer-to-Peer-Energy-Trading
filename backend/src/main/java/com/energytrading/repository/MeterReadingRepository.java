package com.energytrading.repository;

import com.energytrading.model.MeterReading;
import com.energytrading.model.enums.ReadingValidationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MeterReadingRepository extends JpaRepository<MeterReading, Long> {
    Optional<MeterReading> findByReadingId(String readingId);
    boolean existsByReadingId(String readingId);
    List<MeterReading> findByHouseholdIdOrderBySequenceNoDesc(Long householdId);
    Optional<MeterReading> findTopByHouseholdIdOrderBySequenceNoDesc(Long householdId);
    Optional<MeterReading> findTopByHouseholdIdAndValidationStatusOrderBySequenceNoDesc(Long householdId, ReadingValidationStatus status);
    boolean existsByHouseholdIdAndSequenceNo(Long householdId, Long sequenceNo);
    long countByHouseholdIdAndCreatedAtAfter(Long householdId, LocalDateTime since);
    Page<MeterReading> findByHouseholdId(Long householdId, Pageable pageable);
}
