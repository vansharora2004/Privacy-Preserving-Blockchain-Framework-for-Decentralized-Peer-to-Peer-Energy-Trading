-- =========================================================================
-- Migration V002: marketplace and blockchain tracking (schema v1 -> v2, Batch 3)
-- Database: MySQL 8.0.16+
--
-- Purely additive; no table is dropped or recreated and no existing value is changed:
--   * energy_offers:      expires_at (backfilled to created_at + 24h), client_request_id,
--                         unique (seller_id, client_request_id), index (status, expires_at)
--   * trade_transactions: client_request_id, updated_at (backfilled to created_at), blockchain
--                         tracking columns (tx_nonce, submitted_at, submission_attempts, chain_id,
--                         contract_address, record_digest), unique (buyer_id, client_request_id),
--                         idx_tx_hash becomes UNIQUE
--   * new table trade_events (trade state-transition history)
--
-- Usage (runs against the database selected on the command line):
--   mysql -u root -p energy_trading < database/migrations/V002__marketplace_and_blockchain_tracking.sql
-- Prerequisite: V001 (or a database created from schema.sql v1). Take a backup first.
-- A preflight aborts BEFORE any change if the database is not at schema v1 or if existing
-- transaction hashes are duplicated (they could not become unique).
-- MySQL DDL is not transactional: if a later step fails, restore the backup.
-- =========================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS v002_fail $$
CREATE PROCEDURE v002_fail(IN msg VARCHAR(255))
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = msg;
END $$

DROP PROCEDURE IF EXISTS v002_preflight $$
CREATE PROCEDURE v002_preflight()
BEGIN
    IF DATABASE() IS NULL THEN
        CALL v002_fail('V002 aborted: no database selected');
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
         AND table_name IN ('users', 'meter_readings', 'energy_offers', 'trade_transactions',
                            'anomaly_logs', 'audit_logs')) <> 6 THEN
        CALL v002_fail('V002 aborted: expected v1 tables are missing');
    END IF;
    -- v1 marker: V001 / schema.sql v1 created these named constraints
    IF (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()
         AND constraint_name IN ('uk_household_sequence', 'fk_trade_offer', 'chk_trade_amounts')) <> 3 THEN
        CALL v002_fail('V002 aborted: database is not at schema v1 (apply V001 first)');
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
                AND ((table_name = 'energy_offers' AND column_name IN ('expires_at', 'client_request_id'))
                  OR (table_name = 'trade_transactions' AND column_name IN ('client_request_id', 'updated_at', 'tx_nonce'))))
       OR EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'trade_events') THEN
        CALL v002_fail('V002 aborted: v2 columns or tables already exist - database already (partially) migrated');
    END IF;
    IF EXISTS (SELECT 1 FROM trade_transactions WHERE tx_hash IS NOT NULL GROUP BY tx_hash HAVING COUNT(*) > 1) THEN
        CALL v002_fail('V002 aborted: duplicate tx_hash values exist and cannot become unique');
    END IF;
END $$

DELIMITER ;

CALL v002_preflight();

-- -------------------------------------------------------------------------
-- energy_offers
-- -------------------------------------------------------------------------
ALTER TABLE `energy_offers`
    ADD COLUMN `expires_at` DATETIME(6) NULL,
    ADD COLUMN `client_request_id` VARCHAR(64) NULL;

-- Existing offers (if any) get the default 24-hour lifetime from their creation time.
UPDATE `energy_offers` SET `expires_at` = `created_at` + INTERVAL 24 HOUR WHERE `expires_at` IS NULL;

ALTER TABLE `energy_offers`
    MODIFY `expires_at` DATETIME(6) NOT NULL,
    ADD UNIQUE KEY `uk_offer_seller_request` (`seller_id`, `client_request_id`),
    ADD INDEX `idx_offer_status_expiry` (`status`, `expires_at`);

-- -------------------------------------------------------------------------
-- trade_transactions
-- -------------------------------------------------------------------------
ALTER TABLE `trade_transactions`
    ADD COLUMN `client_request_id` VARCHAR(64) NULL,
    ADD COLUMN `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    ADD COLUMN `tx_nonce` BIGINT NULL,
    ADD COLUMN `submitted_at` DATETIME(6) NULL,
    ADD COLUMN `submission_attempts` INT NOT NULL DEFAULT 0,
    ADD COLUMN `chain_id` BIGINT NULL,
    ADD COLUMN `contract_address` VARCHAR(42) NULL,
    ADD COLUMN `record_digest` VARCHAR(64) NULL;

-- Existing trades (if any): last update = creation time (explicit assignment overrides ON UPDATE).
UPDATE `trade_transactions` SET `updated_at` = `created_at`;

ALTER TABLE `trade_transactions`
    DROP INDEX `idx_tx_hash`,
    ADD UNIQUE KEY `idx_tx_hash` (`tx_hash`),
    ADD UNIQUE KEY `uk_trade_buyer_request` (`buyer_id`, `client_request_id`),
    ADD CONSTRAINT `chk_trade_attempts` CHECK (`submission_attempts` >= 0);

-- -------------------------------------------------------------------------
-- trade_events
-- -------------------------------------------------------------------------
CREATE TABLE `trade_events` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `trade_id` BIGINT NOT NULL,
    `from_status` VARCHAR(30),
    `to_status` VARCHAR(30) NOT NULL,
    `actor` VARCHAR(100),
    `detail` VARCHAR(255),
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_trade_event_trade` (`trade_id`, `created_at`),
    CONSTRAINT `fk_trade_event_trade` FOREIGN KEY (`trade_id`) REFERENCES `trade_transactions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS v002_preflight;
DROP PROCEDURE IF EXISTS v002_fail;

SELECT 'V002 migration completed' AS result;
