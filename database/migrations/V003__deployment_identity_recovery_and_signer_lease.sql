-- =========================================================================
-- Migration V003: deployment identity, recovery evidence and signer lease (schema v2 -> v3)
-- Database: MySQL 8.0.16+
--
-- Purely additive; no table is dropped or recreated and no existing value is changed:
--   * trade_transactions: deployment_id, chain_genesis_hash (blockchain deployment identity of the
--     current submission), seller_ref, buyer_ref, privacy_key_id (party references persisted at
--     reservation so verification survives a pseudonym-key rotation), review_reason,
--     reanchor_deployment_id, anchor_checked_at, index (status, anchor_checked_at)
--   * new table trade_submission_attempts (every signed transaction, never overwritten)
--   * new table blockchain_alerts (deduplicated integrity/availability alerts)
--   * new table blockchain_signer_lease (cross-instance signer coordination)
--
-- Existing trades keep NULL in the new columns. They are handled as "legacy" records:
-- verification reports UNVERIFIABLE (not MISMATCH) where party references cannot be recomputed,
-- and the anchor audit establishes their deployment from on-chain evidence.
--
-- Usage: mysql -u root -p energy_trading < database/migrations/V003__deployment_identity_recovery_and_signer_lease.sql
-- Prerequisite: schema v2 (V002). Take a backup first. A preflight aborts before any change if the
-- database is not at v2 or is already migrated. MySQL DDL is not transactional: if a later step
-- fails, restore the backup.
-- =========================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS v003_fail $$
CREATE PROCEDURE v003_fail(IN msg VARCHAR(255))
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = msg;
END $$

DROP PROCEDURE IF EXISTS v003_preflight $$
CREATE PROCEDURE v003_preflight()
BEGIN
    IF DATABASE() IS NULL THEN
        CALL v003_fail('V003 aborted: no database selected');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()
                    AND constraint_name = 'fk_trade_event_trade')
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
                    AND table_name = 'trade_transactions' AND column_name = 'record_digest') THEN
        CALL v003_fail('V003 aborted: database is not at schema v2 (apply V002 first)');
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
                AND table_name = 'trade_transactions' AND column_name IN ('deployment_id', 'seller_ref', 'review_reason'))
       OR EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
                AND table_name IN ('trade_submission_attempts', 'blockchain_alerts', 'blockchain_signer_lease')) THEN
        CALL v003_fail('V003 aborted: v3 columns or tables already exist - database already (partially) migrated');
    END IF;
END $$

DELIMITER ;

CALL v003_preflight();

ALTER TABLE `trade_transactions`
    ADD COLUMN `deployment_id` VARCHAR(66) NULL,
    ADD COLUMN `chain_genesis_hash` VARCHAR(66) NULL,
    ADD COLUMN `seller_ref` VARCHAR(66) NULL,
    ADD COLUMN `buyer_ref` VARCHAR(66) NULL,
    ADD COLUMN `privacy_key_id` VARCHAR(40) NULL,
    ADD COLUMN `review_reason` VARCHAR(255) NULL,
    ADD COLUMN `reanchor_deployment_id` VARCHAR(66) NULL,
    ADD COLUMN `anchor_checked_at` DATETIME(6) NULL,
    ADD INDEX `idx_trade_status_anchor` (`status`, `anchor_checked_at`);

CREATE TABLE `trade_submission_attempts` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `trade_id` BIGINT NOT NULL,
    `attempt_no` INT NOT NULL,
    `purpose` VARCHAR(20) NOT NULL,
    `tx_hash` VARCHAR(66) NOT NULL,
    `nonce` BIGINT NOT NULL,
    `chain_id` BIGINT NOT NULL,
    `contract_address` VARCHAR(42) NOT NULL,
    `deployment_id` VARCHAR(66) NOT NULL,
    `chain_genesis_hash` VARCHAR(66),
    `signer_address` VARCHAR(42) NOT NULL,
    `lease_token` BIGINT NOT NULL,
    `status` VARCHAR(30) NOT NULL,
    `block_number` BIGINT,
    `detail` VARCHAR(255),
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_attempt_tx_hash` (`tx_hash`),
    INDEX `idx_attempt_trade` (`trade_id`, `attempt_no`),
    INDEX `idx_attempt_deployment` (`deployment_id`),
    CONSTRAINT `fk_attempt_trade` FOREIGN KEY (`trade_id`) REFERENCES `trade_transactions` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_attempt_no` CHECK (`attempt_no` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `blockchain_alerts` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `alert_type` VARCHAR(40) NOT NULL,
    `open_key` VARCHAR(191),
    `trade_ref` VARCHAR(64),
    `detail` VARCHAR(500),
    `occurrences` INT NOT NULL DEFAULT 1,
    `first_seen_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `last_seen_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `resolved_at` DATETIME(6),
    `resolved_by` VARCHAR(100),
    `resolution` VARCHAR(255),
    UNIQUE KEY `uk_alert_open_key` (`open_key`),
    INDEX `idx_alert_type` (`alert_type`, `resolved_at`),
    CONSTRAINT `chk_alert_occurrences` CHECK (`occurrences` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `blockchain_signer_lease` (
    `signer_address` VARCHAR(42) PRIMARY KEY,
    `holder_id` VARCHAR(64) NOT NULL,
    `fencing_token` BIGINT NOT NULL,
    `acquired_at` DATETIME(6) NOT NULL,
    `renewed_at` DATETIME(6) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    CONSTRAINT `chk_lease_token` CHECK (`fencing_token` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS v003_preflight;
DROP PROCEDURE IF EXISTS v003_fail;

SELECT 'V003 migration completed' AS result;
