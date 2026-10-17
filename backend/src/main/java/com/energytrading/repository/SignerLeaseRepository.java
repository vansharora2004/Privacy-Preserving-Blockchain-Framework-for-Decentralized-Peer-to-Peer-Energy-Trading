package com.energytrading.repository;

import com.energytrading.model.SignerLease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SignerLeaseRepository extends JpaRepository<SignerLease, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SignerLease l where l.signerAddress = :signer")
    Optional<SignerLease> lock(@Param("signer") String signerAddress);

    /** Database clock, so that lease expiry does not depend on instance clocks. */
    @Query(value = "select localtimestamp(6)", nativeQuery = true)
    java.sql.Timestamp databaseNow();
}
