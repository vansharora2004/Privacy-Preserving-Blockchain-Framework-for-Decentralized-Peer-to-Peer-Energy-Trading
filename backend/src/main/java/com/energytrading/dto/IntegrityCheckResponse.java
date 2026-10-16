package com.energytrading.dto;

import java.time.LocalDateTime;

/** Result of recomputing a record digest. Contains no record contents. */
public record IntegrityCheckResponse(String readingId,
                                     IntegrityResult result,
                                     String digestFormat,
                                     LocalDateTime checkedAt,
                                     String note) {

    public enum IntegrityResult { MATCH, MISMATCH, NO_STORED_DIGEST }
}
