-- =========================================================================
-- Migration V001: align a Hibernate-generated (ddl-auto=update) database
--                 with the versioned schema in database/schema.sql (v1)
-- Database: MySQL 8.0.16+
--
-- Converts, without dropping or recreating any table and without losing data:
--   * database/table collation  utf8mb4_0900_ai_ci -> utf8mb4_unicode_ci
--   * 7 ENUM columns            -> VARCHAR(30) (stored labels are preserved)
--   * column defaults           (timestamps, balances, energy, statuses)
--   * Hibernate FK/index names  -> explicit names with ON DELETE rules
--   * adds uk_household_sequence and the 8 CHECK constraints
--
-- Usage (runs against the database selected on the command line; it does not
-- hardcode a database name, so it can be rehearsed on a copy first):
--   mysql -u root -p energy_trading < database/migrations/V001__align_hibernate_generated_schema.sql
--
-- Safety:
--   * Take a backup first (see database/migrations/README.md).
--   * A preflight step validates the current structure and data and aborts
--     with an error BEFORE any change if the database is not in the expected
--     legacy state (e.g. already migrated) or if existing rows would violate
--     a new constraint. Constraints are never weakened to make data fit:
--     fix the reported rows, then rerun.
--   * MySQL DDL is not transactional. If a later step fails, restore the backup.
-- =========================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS v001_fail $$
CREATE PROCEDURE v001_fail(IN msg VARCHAR(255))
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = msg;
END $$

DROP PROCEDURE IF EXISTS v001_preflight $$
CREATE PROCEDURE v001_preflight()
BEGIN
    DECLARE v_msg VARCHAR(255);

    IF DATABASE() IS NULL THEN
        CALL v001_fail('V001 aborted: no database selected');
    END IF;

    -- ---------------------------------------------------------------------
    -- 1. Structure: all six tables exist and the database is still legacy
    -- ---------------------------------------------------------------------
    IF (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_name IN ('users', 'meter_readings', 'energy_offers',
                              'trade_transactions', 'anomaly_logs', 'audit_logs')) <> 6 THEN
        CALL v001_fail('V001 aborted: expected tables are missing');
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND data_type = 'enum'
           AND (table_name, column_name) IN (('users', 'role'),
                                             ('meter_readings', 'validation_status'),
                                             ('energy_offers', 'status'),
                                             ('trade_transactions', 'status'),
                                             ('anomaly_logs', 'severity'),
                                             ('anomaly_logs', 'review_status'))) <> 6 THEN
        CALL v001_fail('V001 aborted: enum columns not found - database already migrated or not a legacy Hibernate schema');
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_name IN ('uk_household_sequence', 'fk_reading_household', 'fk_offer_seller',
                                          'fk_offer_source_reading', 'fk_trade_offer', 'fk_trade_buyer',
                                          'fk_trade_seller', 'chk_users_balance', 'chk_users_energy',
                                          'chk_reading_sequence', 'chk_reading_energy', 'chk_offer_quantity',
                                          'chk_offer_price', 'chk_trade_quantity', 'chk_trade_amounts')) THEN
        CALL v001_fail('V001 aborted: target constraints already exist - database already (partially) migrated');
    END IF;

    -- ---------------------------------------------------------------------
    -- 2. Data: enum values must be valid labels ('' = invalid value stored
    --    by a non-strict insert, which would not survive a typed column)
    -- ---------------------------------------------------------------------
    IF EXISTS (SELECT 1 FROM users WHERE role = '')
       OR EXISTS (SELECT 1 FROM meter_readings WHERE validation_status = '')
       OR EXISTS (SELECT 1 FROM energy_offers WHERE status = '')
       OR EXISTS (SELECT 1 FROM trade_transactions WHERE status = '')
       OR EXISTS (SELECT 1 FROM anomaly_logs WHERE severity = '' OR review_status = '') THEN
        CALL v001_fail('V001 aborted: rows contain invalid empty enum values');
    END IF;

    -- ---------------------------------------------------------------------
    -- 3. Data: rows that would violate the new unique key
    -- ---------------------------------------------------------------------
    IF EXISTS (SELECT 1 FROM meter_readings GROUP BY household_id, sequence_no HAVING COUNT(*) > 1) THEN
        CALL v001_fail('V001 aborted: duplicate (household_id, sequence_no) in meter_readings');
    END IF;

    -- ---------------------------------------------------------------------
    -- 4. Data: the collation change must not make existing unique values
    --    collide (utf8mb4_unicode_ci is PAD SPACE and compares differently)
    -- ---------------------------------------------------------------------
    IF EXISTS (SELECT 1 FROM users GROUP BY household_code COLLATE utf8mb4_unicode_ci HAVING COUNT(*) > 1)
       OR EXISTS (SELECT 1 FROM users WHERE email IS NOT NULL GROUP BY email COLLATE utf8mb4_unicode_ci HAVING COUNT(*) > 1)
       OR EXISTS (SELECT 1 FROM meter_readings GROUP BY reading_id COLLATE utf8mb4_unicode_ci HAVING COUNT(*) > 1)
       OR EXISTS (SELECT 1 FROM energy_offers GROUP BY offer_id COLLATE utf8mb4_unicode_ci HAVING COUNT(*) > 1)
       OR EXISTS (SELECT 1 FROM trade_transactions GROUP BY trade_id COLLATE utf8mb4_unicode_ci HAVING COUNT(*) > 1) THEN
        CALL v001_fail('V001 aborted: unique identifiers would collide under utf8mb4_unicode_ci');
    END IF;

    -- ---------------------------------------------------------------------
    -- 5. Data: rows that would violate the new CHECK constraints
    -- ---------------------------------------------------------------------
    SET v_msg = CASE
        WHEN EXISTS (SELECT 1 FROM users WHERE NOT (balance >= 0)) THEN 'users.balance < 0 (chk_users_balance)'
        WHEN EXISTS (SELECT 1 FROM users WHERE NOT (current_surplus_kwh >= 0 AND current_deficit_kwh >= 0)) THEN 'negative users surplus/deficit (chk_users_energy)'
        WHEN EXISTS (SELECT 1 FROM meter_readings WHERE NOT (sequence_no > 0)) THEN 'meter_readings.sequence_no <= 0 (chk_reading_sequence)'
        WHEN EXISTS (SELECT 1 FROM meter_readings WHERE NOT (energy_generated_kwh >= 0 AND energy_consumed_kwh >= 0)) THEN 'negative meter reading energy (chk_reading_energy)'
        WHEN EXISTS (SELECT 1 FROM energy_offers WHERE NOT (quantity_kwh > 0 AND remaining_kwh >= 0 AND remaining_kwh <= quantity_kwh)) THEN 'invalid energy_offers quantities (chk_offer_quantity)'
        WHEN EXISTS (SELECT 1 FROM energy_offers WHERE NOT (price_per_kwh > 0)) THEN 'energy_offers.price_per_kwh <= 0 (chk_offer_price)'
        WHEN EXISTS (SELECT 1 FROM trade_transactions WHERE NOT (quantity_kwh > 0)) THEN 'trade_transactions.quantity_kwh <= 0 (chk_trade_quantity)'
        WHEN EXISTS (SELECT 1 FROM trade_transactions WHERE NOT (price_per_kwh > 0 AND total_amount >= 0)) THEN 'invalid trade_transactions amounts (chk_trade_amounts)'
        ELSE NULL END;
    IF v_msg IS NOT NULL THEN
        SET v_msg = CONCAT('V001 aborted: existing rows violate ', v_msg);
        CALL v001_fail(v_msg);
    END IF;

    -- ---------------------------------------------------------------------
    -- 6. Data: orphaned references (the new foreign keys would be rejected)
    -- ---------------------------------------------------------------------
    IF EXISTS (SELECT 1 FROM meter_readings r LEFT JOIN users u ON u.id = r.household_id WHERE u.id IS NULL)
       OR EXISTS (SELECT 1 FROM energy_offers o LEFT JOIN users u ON u.id = o.seller_id WHERE u.id IS NULL)
       OR EXISTS (SELECT 1 FROM energy_offers o LEFT JOIN meter_readings r ON r.id = o.source_reading_id
                   WHERE o.source_reading_id IS NOT NULL AND r.id IS NULL)
       OR EXISTS (SELECT 1 FROM trade_transactions t LEFT JOIN energy_offers o ON o.id = t.offer_id
                   WHERE t.offer_id IS NOT NULL AND o.id IS NULL)
       OR EXISTS (SELECT 1 FROM trade_transactions t LEFT JOIN users u ON u.id = t.buyer_id WHERE u.id IS NULL)
       OR EXISTS (SELECT 1 FROM trade_transactions t LEFT JOIN users u ON u.id = t.seller_id WHERE u.id IS NULL) THEN
        CALL v001_fail('V001 aborted: orphaned foreign-key references found');
    END IF;
END $$

-- Drops the (Hibernate-named) foreign key on table.column, whatever its name.
DROP PROCEDURE IF EXISTS v001_drop_fk $$
CREATE PROCEDURE v001_drop_fk(IN p_table VARCHAR(64), IN p_column VARCHAR(64))
BEGIN
    DECLARE v_name VARCHAR(64);
    SELECT k.constraint_name INTO v_name
      FROM information_schema.key_column_usage k
     WHERE k.table_schema = DATABASE() AND k.table_name = p_table AND k.column_name = p_column
       AND k.referenced_table_name IS NOT NULL
     LIMIT 1;
    IF v_name IS NULL THEN
        CALL v001_fail(CONCAT('V001 failed: no foreign key found on ', p_table, '.', p_column));
    END IF;
    SET @v001_sql = CONCAT('ALTER TABLE `', p_table, '` DROP FOREIGN KEY `', v_name, '`');
    PREPARE stmt FROM @v001_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
END $$

-- Renames the single-column index Hibernate created for a foreign key.
DROP PROCEDURE IF EXISTS v001_rename_fk_index $$
CREATE PROCEDURE v001_rename_fk_index(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_new_name VARCHAR(64))
BEGIN
    DECLARE v_name VARCHAR(64);
    SELECT s.index_name INTO v_name
      FROM information_schema.statistics s
     WHERE s.table_schema = DATABASE() AND s.table_name = p_table AND s.column_name = p_column
       AND s.seq_in_index = 1 AND s.index_name <> p_new_name
       AND (SELECT COUNT(*) FROM information_schema.statistics s2
             WHERE s2.table_schema = s.table_schema AND s2.table_name = s.table_name
               AND s2.index_name = s.index_name) = 1
     LIMIT 1;
    IF v_name IS NULL THEN
        SET @v001_sql = CONCAT('CREATE INDEX `', p_new_name, '` ON `', p_table, '` (`', p_column, '`)');
    ELSE
        SET @v001_sql = CONCAT('ALTER TABLE `', p_table, '` RENAME INDEX `', v_name, '` TO `', p_new_name, '`');
    END IF;
    PREPARE stmt FROM @v001_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
END $$

DELIMITER ;

-- -------------------------------------------------------------------------
-- Step 0: preflight (aborts the script before any change on failure)
-- -------------------------------------------------------------------------
CALL v001_preflight();

-- -------------------------------------------------------------------------
-- Step 1: drop legacy foreign keys (recreated with names and rules in step 5)
-- -------------------------------------------------------------------------
CALL v001_drop_fk('trade_transactions', 'offer_id');
CALL v001_drop_fk('trade_transactions', 'buyer_id');
CALL v001_drop_fk('trade_transactions', 'seller_id');
CALL v001_drop_fk('energy_offers', 'seller_id');
CALL v001_drop_fk('energy_offers', 'source_reading_id');
CALL v001_drop_fk('meter_readings', 'household_id');

-- -------------------------------------------------------------------------
-- Step 2: character set / collation
-- -------------------------------------------------------------------------
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `users`              CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `meter_readings`     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `energy_offers`      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `trade_transactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `anomaly_logs`       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `audit_logs`         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------
-- Step 3: column types and defaults (ENUM -> VARCHAR keeps the stored labels)
-- -------------------------------------------------------------------------
ALTER TABLE `users`
    MODIFY `role` VARCHAR(30) NOT NULL,
    MODIFY `balance` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    MODIFY `current_surplus_kwh` DOUBLE NOT NULL DEFAULT 0.0,
    MODIFY `current_deficit_kwh` DOUBLE NOT NULL DEFAULT 0.0,
    MODIFY `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

ALTER TABLE `meter_readings`
    MODIFY `validation_status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    MODIFY `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

ALTER TABLE `energy_offers`
    MODIFY `status` VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    MODIFY `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

ALTER TABLE `trade_transactions`
    MODIFY `status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    MODIFY `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

ALTER TABLE `anomaly_logs`
    MODIFY `severity` VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    MODIFY `review_status` VARCHAR(30) NOT NULL DEFAULT 'DETECTED',
    MODIFY `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

ALTER TABLE `audit_logs`
    MODIFY `timestamp` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- -------------------------------------------------------------------------
-- Step 4: unique key, indexes and CHECK constraints
-- -------------------------------------------------------------------------
ALTER TABLE `users`
    ADD CONSTRAINT `chk_users_balance` CHECK (`balance` >= 0),
    ADD CONSTRAINT `chk_users_energy` CHECK (`current_surplus_kwh` >= 0 AND `current_deficit_kwh` >= 0);

ALTER TABLE `meter_readings`
    ADD CONSTRAINT `uk_household_sequence` UNIQUE (`household_id`, `sequence_no`),
    ADD CONSTRAINT `chk_reading_sequence` CHECK (`sequence_no` > 0),
    ADD CONSTRAINT `chk_reading_energy` CHECK (`energy_generated_kwh` >= 0 AND `energy_consumed_kwh` >= 0);

CALL v001_rename_fk_index('energy_offers', 'source_reading_id', 'idx_offer_source_reading');
ALTER TABLE `energy_offers`
    ADD CONSTRAINT `chk_offer_quantity` CHECK (`quantity_kwh` > 0 AND `remaining_kwh` >= 0 AND `remaining_kwh` <= `quantity_kwh`),
    ADD CONSTRAINT `chk_offer_price` CHECK (`price_per_kwh` > 0);

CALL v001_rename_fk_index('trade_transactions', 'offer_id', 'idx_trade_offer');
ALTER TABLE `trade_transactions`
    ADD CONSTRAINT `chk_trade_quantity` CHECK (`quantity_kwh` > 0),
    ADD CONSTRAINT `chk_trade_amounts` CHECK (`price_per_kwh` > 0 AND `total_amount` >= 0);

-- -------------------------------------------------------------------------
-- Step 5: foreign keys with explicit names and ON DELETE rules
-- -------------------------------------------------------------------------
ALTER TABLE `meter_readings`
    ADD CONSTRAINT `fk_reading_household` FOREIGN KEY (`household_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `energy_offers`
    ADD CONSTRAINT `fk_offer_seller` FOREIGN KEY (`seller_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    ADD CONSTRAINT `fk_offer_source_reading` FOREIGN KEY (`source_reading_id`) REFERENCES `meter_readings` (`id`) ON DELETE SET NULL;

ALTER TABLE `trade_transactions`
    ADD CONSTRAINT `fk_trade_offer` FOREIGN KEY (`offer_id`) REFERENCES `energy_offers` (`id`) ON DELETE SET NULL,
    ADD CONSTRAINT `fk_trade_buyer` FOREIGN KEY (`buyer_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    ADD CONSTRAINT `fk_trade_seller` FOREIGN KEY (`seller_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT;

-- -------------------------------------------------------------------------
-- Cleanup
-- -------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS v001_preflight;
DROP PROCEDURE IF EXISTS v001_drop_fk;
DROP PROCEDURE IF EXISTS v001_rename_fk_index;
DROP PROCEDURE IF EXISTS v001_fail;

SELECT 'V001 migration completed' AS result;
