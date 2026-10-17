# Database Migrations

`database/schema.sql` is the authoritative schema for **new** databases. The scripts in this
folder upgrade **existing** databases to that schema without dropping tables or losing data.
Migrations run against whichever database you select on the command line, so you can rehearse
one on a copy first.

| Version | File | Upgrades |
|---|---|---|
| V001 | `V001__align_hibernate_generated_schema.sql` | A database created by Hibernate `ddl-auto=update` (before the versioned schema) → schema v1 |
| V002 | `V002__marketplace_and_blockchain_tracking.sql` | schema v1 → v2 (Batch 3): offer expiry and idempotency keys, trade blockchain-tracking columns, unique `tx_hash`, new `trade_events` table |
| V003 | `V003__deployment_identity_recovery_and_signer_lease.sql` | schema v2 → v3 (Batch 3 remediation): deployment identity, stored party references, review and anchor tracking, `trade_submission_attempts`, `blockchain_alerts`, `blockchain_signer_lease` |

Apply migrations in order. `database/schema.sql` always describes the latest version (currently v3).
The backend validates the schema at startup (`JPA_DDL_AUTO=validate`), so after upgrading the
code it does not start until the database is at the version it expects.

## V003 (Batch 3 remediation)
V003 is purely additive: no table or column is dropped and no existing value changes.
- `trade_transactions` gains nullable `deployment_id`, `chain_genesis_hash`, `seller_ref`,
  `buyer_ref`, `privacy_key_id`, `review_reason`, `reanchor_deployment_id`, `anchor_checked_at`
  and the index `idx_trade_status_anchor`. Existing rows keep NULL in all of them: confirmed trades
  get their deployment recorded on their first successful anchor audit, and trades without stored
  party references keep deriving them from the current pseudonym key.
- New tables `trade_submission_attempts` (one row per signed transaction; unique `tx_hash`,
  foreign key to the trade), `blockchain_alerts` (de-duplicated by `open_key`) and
  `blockchain_signer_lease` (one row per signer address; fencing token). They start empty.
- The raw privacy key is never stored; `privacy_key_id` is a one-way identifier of it.

The preflight aborts if the database is not at v2 (V002 not applied) or if V003 was already
applied. Rehearsal (performed for this release on disposable databases): a fresh v1 database
upgraded with V002 and V003 is identical to a fresh `schema.sql` build; a copy of an existing v2
database upgrades with every row unchanged; a second run and a v1 database both abort before any
change. Follow the same back-up / rehearse / apply procedure as below, with the V003 file name.

## V002 (Batch 3)
V002 is purely additive: no table is dropped and no existing value changes. Pre-existing offers get
`expires_at = created_at + 24h`, and pre-existing trades get `updated_at = created_at`. The preflight
aborts if the database is not at v1, if it is already migrated, or if duplicate transaction hashes
would block the new unique key. The procedure is the same as for V001: back up, restore the backup
into a scratch copy, rehearse there, then apply.

## When V001 is needed
Run V001 if the backend fails to start with:
```
Schema-validation: wrong column type encountered in column [...]; found [enum (Types#CHAR)], but expecting [varchar(30) (Types#VARCHAR)]
```
V001 converts the 7 ENUM columns to `VARCHAR(30)` (keeping the stored values) and the collation
to `utf8mb4_unicode_ci`. It adds column defaults, the `uk_household_sequence` unique key and the
8 CHECK constraints, and it recreates the foreign keys with explicit names and ON DELETE rules.

Before changing anything, a preflight step aborts with an error if:
- the database is already migrated, or is not in the expected legacy state;
- rows would violate a new constraint (duplicate household sequence numbers, negative
  balances or energy, invalid offer or trade amounts);
- unique identifiers would collide under the new collation;
- foreign-key references are orphaned.

Constraints are never relaxed to make data fit. Fix the reported rows, then rerun. An aborted
run may leave helper procedures named `v001_*` behind. They are harmless and get replaced on
the next run.

## Procedure (Docker container `mysql-energy` shown; adapt for a local client)
```bash
# 1. Back up (database/backups/ is git-ignored)
docker exec mysql-energy sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces energy_trading' > database/backups/energy_trading_pre_V001.sql

# 2. Verify the backup: restore it into a scratch copy and rehearse the migration there
docker exec mysql-energy sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE et_copy CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"'
docker exec -i mysql-energy sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" et_copy' < database/backups/energy_trading_pre_V001.sql
docker exec -i mysql-energy sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" et_copy' < database/migrations/V001__align_hibernate_generated_schema.sql

# 3. Apply to the real database, then start the backend (JPA_DDL_AUTO=validate, the default)
docker exec -i mysql-energy sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" energy_trading' < database/migrations/V001__align_hibernate_generated_schema.sql
```
MySQL DDL is not transactional. If a step fails after the preflight, restore the backup:
```bash
docker exec -i mysql-energy sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" energy_trading' < database/backups/energy_trading_pre_V001.sql
```
