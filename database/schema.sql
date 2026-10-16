-- =========================================================================
-- Privacy-Preserving & Trust-Aware P2P Energy Trading Database Schema
-- Database: MySQL 8.0+
-- =========================================================================

CREATE DATABASE IF NOT EXISTS `energy_trading` 
  CHARACTER SET utf8mb4 
  COLLATE utf8mb4_unicode_ci;

USE `energy_trading`;

-- 1. Users Table (Households, Admin, Auditor)
CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `household_code` VARCHAR(50) NOT NULL UNIQUE,
    `display_alias` VARCHAR(100) NOT NULL,
    `full_name` VARCHAR(150),
    `email` VARCHAR(150) UNIQUE,
    `password_hash` VARCHAR(255) NOT NULL,
    `role` VARCHAR(30) NOT NULL,
    `balance` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    `current_surplus_kwh` DOUBLE NOT NULL DEFAULT 0.0,
    `current_deficit_kwh` DOUBLE NOT NULL DEFAULT 0.0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user_household_code` (`household_code`),
    INDEX `idx_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Meter Readings Table (Smart Meter measurements)
CREATE TABLE IF NOT EXISTS `meter_readings` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `household_id` BIGINT NOT NULL,
    `reading_id` VARCHAR(64) NOT NULL UNIQUE,
    `sequence_no` BIGINT NOT NULL,
    `energy_generated_kwh` DOUBLE NOT NULL,
    `energy_consumed_kwh` DOUBLE NOT NULL,
    `available_kwh` DOUBLE NOT NULL,
    `reading_time` DATETIME NOT NULL,
    `validation_status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    `rejection_reason` VARCHAR(255),
    `record_hash` VARCHAR(64),
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`household_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    INDEX `idx_reading_id` (`reading_id`),
    INDEX `idx_household_sequence` (`household_id`, `sequence_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Energy Offers Table (Marketplace listings)
CREATE TABLE IF NOT EXISTS `energy_offers` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `seller_id` BIGINT NOT NULL,
    `source_reading_id` BIGINT,
    `offer_id` VARCHAR(64) NOT NULL UNIQUE,
    `quantity_kwh` DOUBLE NOT NULL,
    `remaining_kwh` DOUBLE NOT NULL,
    `price_per_kwh` DECIMAL(10, 2) NOT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`seller_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    FOREIGN KEY (`source_reading_id`) REFERENCES `meter_readings` (`id`) ON DELETE SET NULL,
    INDEX `idx_offer_id` (`offer_id`),
    INDEX `idx_offer_status` (`status`),
    INDEX `idx_offer_seller` (`seller_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Trade Transactions Table (Trades & EVM link)
CREATE TABLE IF NOT EXISTS `trade_transactions` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `trade_id` VARCHAR(64) NOT NULL UNIQUE,
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
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `confirmed_at` DATETIME,
    FOREIGN KEY (`offer_id`) REFERENCES `energy_offers` (`id`) ON DELETE SET NULL,
    FOREIGN KEY (`buyer_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    FOREIGN KEY (`seller_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    INDEX `idx_trade_id` (`trade_id`),
    INDEX `idx_tx_hash` (`tx_hash`),
    INDEX `idx_trade_status` (`status`)
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
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
    `timestamp` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_audit_actor` (`actor_username`),
    INDEX `idx_audit_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
