# Database Migrations

`database/schema.sql` is the authoritative schema for **new** databases. The scripts in this
folder upgrade **existing** databases to that schema without dropping tables or losing data.
Migrations run against whichever database you select on the command line, so you can rehearse
one on a copy first.

| Version | File | Upgrades |
|---|---|---|
| V001 | `V001__align_hibernate_generated_schema.sql` | A database created by Hibernate `ddl-auto=update` (before the versioned schema) → schema v1 |

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
