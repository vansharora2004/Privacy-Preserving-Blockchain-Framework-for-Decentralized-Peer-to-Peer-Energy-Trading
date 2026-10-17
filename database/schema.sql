-- =========================================================================
-- Privacy-Preserving & Trust-Aware P2P Energy Trading Database Schema
-- Database: MySQL 8.0.16+ (CHECK constraints are enforced from 8.0.16)
--
-- This file is the authoritative, versioned schema. It must stay consistent
-- with the JPA entities in backend/src/main/java/com/energytrading/model.
-- Enum-backed columns are VARCHAR(30) and store the Java enum constant name.
-- Schema version: 3 (Batch 3 remediation). Existing databases: apply migrations/V002 and V003 in order.
--
-- Index, unique-key and foreign-key names are explicit so that a database
-- created from this file and one upgraded by database/migrations/ end up
-- structurally identical.
-- =========================================================================

CREATE DATABASE IF NOT EXISTS `energy_trading` 
  CHARACTER SET utf8mb4 
  COLLATE utf8mb4_unicode_ci;

USE `energy_trading`;

-- 1. Users Table (Households, Admin, Auditor)
CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `household_code` VARCHAR(50) NOT NULL,
    `display_alias` VARCHAR(100) NOT NULL,
    `full_name` VARCHAR(150),
    `email` VARCHAR(150),
    `password_hash` VARCHAR(255) NOT NULL,
    `role` VARCHAR(30) NOT NULL,
    `balance` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    `current_surplus_kwh` DOUBLE NOT NULL DEFAULT 0.0,
    `current_deficit_kwh` DOUBLE NOT NULL DEFAULT 0.0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `idx_user_household_code` (`household_code`),
    UNIQUE KEY `idx_user_email` (`email`),
    CONSTRAINT `chk_users_balance` CHECK (`balance` >= 0),
    CONSTRAINT `chk_users_energy` CHECK (`current_surplus_kwh` >= 0 AND `current_deficit_kwh` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Meter Readings Table (Smart Meter measurements)
CREATE TABLE IF NOT EXISTS `meter_readings` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `household_id` BIGINT NOT NULL,
    `reading_id` VARCHAR(64) NOT NULL,
    `sequence_no` BIGINT NOT NULL,
    `energy_generated_kwh` DOUBLE NOT NULL,
    `energy_consumed_kwh` DOUBLE NOT NULL,
    `available_kwh` DOUBLE NOT NULL,
    `reading_time` DATETIME(6) NOT NULL,
    `validation_status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    `rejection_reason` VARCHAR(255),
    `record_hash` VARCHAR(64),
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `idx_reading_id` (`reading_id`),
    INDEX `idx_household_sequence` (`household_id`, `sequence_no`),
    -- A household may never reuse a sequence number (replay protection)
    CONSTRAINT `uk_household_sequence` UNIQUE (`household_id`, `sequence_no`),
    CONSTRAINT `fk_reading_household` FOREIGN KEY (`household_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_reading_sequence` CHECK (`sequence_no` > 0),
    CONSTRAINT `chk_reading_energy` CHECK (`energy_generated_kwh` >= 0 AND `energy_consumed_kwh` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Energy Offers Table (Marketplace listings)
CREATE TABLE IF NOT EXISTS `energy_offers` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `seller_id` BIGINT NOT NULL,
    `source_reading_id` BIGINT,
    `offer_id` VARCHAR(64) NOT NULL,
    `quantity_kwh` DOUBLE NOT NULL,
    `remaining_kwh` DOUBLE NOT NULL,
    `price_per_kwh` DECIMAL(10, 2) NOT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `expires_at` DATETIME(6) NOT NULL,
    `client_request_id` VARCHAR(64),
    UNIQUE KEY `idx_offer_id` (`offer_id`),
    UNIQUE KEY `uk_offer_seller_request` (`seller_id`, `client_request_id`),
    INDEX `idx_offer_status_expiry` (`status`, `expires_at`),
    INDEX `idx_offer_status` (`status`),
    INDEX `idx_offer_seller` (`seller_id`),
    INDEX `idx_offer_source_reading` (`source_reading_id`),
    CONSTRAINT `fk_offer_seller` FOREIGN KEY (`seller_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_offer_source_reading` FOREIGN KEY (`source_reading_id`) REFERENCES `meter_readings` (`id`) ON DELETE SET NULL,
    CONSTRAINT `chk_offer_quantity` CHECK (`quantity_kwh` > 0 AND `remaining_kwh` >= 0 AND `remaining_kwh` <= `quantity_kwh`),
    CONSTRAINT `chk_offer_price` CHECK (`price_per_kwh` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Trade Transactions Table (Trades & EVM link)
CREATE TABLE IF NOT EXISTS `trade_transactions` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `trade_id` VARCHAR(64) NOT NULL,
    `offer_id` BIGINT,
    `buyer_id` BIGINT NOT NULL,
    `seller_id` BIGINT NOT NULL,
    `quantity_kwh` DOUBLE NOT NULL,
    `price_per_kwh` DECIMAL(10, 2) NOT NULL,
    `total_amount` DECIMAL(12, 2) NOT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    `tx_hash` VARCHAR(66),
    `block_number` BIGINT,
    `rejection_reason` VARCHAR(255),
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `confirmed_at` DATETIME(6),
    `client_request_id` VARCHAR(64),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `tx_nonce` BIGINT,
    `submitted_at` DATETIME(6),
    `submission_attempts` INT NOT NULL DEFAULT 0,
    `chain_id` BIGINT,
    `contract_address` VARCHAR(42),
    `record_digest` VARCHAR(64),
    `deployment_id` VARCHAR(66),
    `chain_genesis_hash` VARCHAR(66),
    `seller_ref` VARCHAR(66),
    `buyer_ref` VARCHAR(66),
    `privacy_key_id` VARCHAR(40),
    `review_reason` VARCHAR(255),
    `reanchor_deployment_id` VARCHAR(66),
    `anchor_checked_at` DATETIME(6),
    UNIQUE KEY `idx_trade_id` (`trade_id`),
    INDEX `idx_trade_status_anchor` (`status`, `anchor_checked_at`),
    UNIQUE KEY `idx_tx_hash` (`tx_hash`),
    UNIQUE KEY `uk_trade_buyer_request` (`buyer_id`, `client_request_id`),
    INDEX `idx_buyer_id` (`buyer_id`),
    INDEX `idx_seller_id` (`seller_id`),
    INDEX `idx_trade_status` (`status`),
    INDEX `idx_trade_offer` (`offer_id`),
    CONSTRAINT `fk_trade_offer` FOREIGN KEY (`offer_id`) REFERENCES `energy_offers` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_trade_buyer` FOREIGN KEY (`buyer_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_trade_seller` FOREIGN KEY (`seller_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_trade_quantity` CHECK (`quantity_kwh` > 0),
    CONSTRAINT `chk_trade_amounts` CHECK (`price_per_kwh` > 0 AND `total_amount` >= 0),
    CONSTRAINT `chk_trade_attempts` CHECK (`submission_attempts` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4b. Trade Events Table (trade state-transition history, schema v2)
CREATE TABLE IF NOT EXISTS `trade_events` (
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

-- 4c. Trade Submission Attempts (every signed transaction, schema v3)
CREATE TABLE IF NOT EXISTS `trade_submission_attempts` (
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

-- 4d. Blockchain Alerts (deduplicated integrity and availability alerts, schema v3)
CREATE TABLE IF NOT EXISTS `blockchain_alerts` (
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

-- 4e. Blockchain Signer Lease (cross-instance signer coordination, schema v3)
CREATE TABLE IF NOT EXISTS `blockchain_signer_lease` (
    `signer_address` VARCHAR(42) PRIMARY KEY,
    `holder_id` VARCHAR(64) NOT NULL,
    `fencing_token` BIGINT NOT NULL,
    `acquired_at` DATETIME(6) NOT NULL,
    `renewed_at` DATETIME(6) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    CONSTRAINT `chk_lease_token` CHECK (`fencing_token` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Anomaly Logs Table (Rule-based detection events)
CREATE TABLE IF NOT EXISTS `anomaly_logs` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `event_type` VARCHAR(50),
    `target_reference_id` VARCHAR(64),
    `rule_code` VARCHAR(64) NOT NULL,
    `severity` VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    `description` VARCHAR(500) NOT NULL,
    `review_status` VARCHAR(30) NOT NULL DEFAULT 'DETECTED',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_anomaly_target` (`target_reference_id`),
    INDEX `idx_anomaly_rule_code` (`rule_code`),
    INDEX `idx_anomaly_review_status` (`review_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Audit Logs Table (Security-relevant actions)
CREATE TABLE IF NOT EXISTS `audit_logs` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `actor_username` VARCHAR(100),
    `action` VARCHAR(100) NOT NULL,
    `outcome` VARCHAR(50) NOT NULL,
    `details` VARCHAR(1000),
    `ip_address` VARCHAR(50),
    `timestamp` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_audit_actor` (`actor_username`),
    INDEX `idx_audit_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
