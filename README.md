# Privacy-Preserving and Trust-Aware Blockchain Framework for Decentralized Peer-to-Peer Energy Trading

A prototype peer-to-peer (P2P) energy trading framework combining smart-meter validation, privacy-preserving data handling, rule-based anomaly detection, and a local EVM blockchain smart contract.

---

## Project Structure

```text
├── backend/                  # Java 21 & Spring Boot application
│   ├── pom.xml               # Maven configuration
│   └── src/
│       ├── main/
│       │   ├── java/com/energytrading/
│       │   │   ├── EnergyTradingApplication.java
│       │   │   ├── config/   # Security, CORS, and DataInitializer
│       │   │   ├── controller/ # REST endpoints (Health, etc.)
│       │   │   ├── model/    # JPA Entities & Enums
│       │   │   ├── repository/ # Spring Data JPA Repositories
│       │   │   └── service/  # Core business services (Batch 2+)
│       │   └── resources/
│       │       └── application.properties
│       └── test/             # JUnit, @DataJpaTest & MockMvc tests (+ opt-in MySQL IT)
├── blockchain/               # Hardhat EVM local development environment
│   ├── contracts/            # Solidity smart contracts (Batch 3)
│   ├── scripts/              # Deployment and test scripts
│   ├── test/                 # Hardhat contract tests
│   ├── hardhat.config.js     # Hardhat network & compiler configuration
│   └── package.json
├── database/
│   ├── schema.sql            # Versioned MySQL DDL and schema definition (new databases)
│   └── migrations/           # In-place upgrades for existing databases (V001, ...)
├── docs/                     # Architectural & specification documents
│   ├── architecture.md
│   ├── privacy-and-security.md # Batch 2 privacy, integrity and access-control design
│   ├── implementation.md
│   └── problemstatement.md
├── .env.example              # Environment variables template
└── .gitignore                # Comprehensive exclusions for Java, Node, IDEs & secrets
```

---

## Quickstart & Local Execution

### 1. Prerequisites
- **Java**: JDK 21+ (the build targets Java 21 bytecode; JDK 22 also verified)
- **Maven**: 3.9+
- **Node.js**: v20+ (v24 verified) with npm
- **MySQL**: 8.0.16+ (CHECK constraints are enforced from 8.0.16), local install or Docker

### 2. Environment Configuration
All settings come from environment variables. See [`.env.example`](.env.example) for the full list.
Spring Boot does **not** read `.env` files automatically, so export the variables in your shell
(or IDE run configuration):
```powershell
# Windows PowerShell
$env:DB_HOST="localhost"
$env:DB_PORT="3306"
$env:DB_NAME="energy_trading"
$env:DB_USERNAME="root"
$env:DB_PASSWORD="<your MySQL password>"
# REQUIRED (Batch 2): secret key for household pseudonyms, at least 32 characters
$env:PRIVACY_PSEUDONYM_KEY = python -c "import secrets; print(secrets.token_hex(32))"
# Optional: passwords for the seeded demo accounts (otherwise random, unusable passwords)
$env:DEMO_HOUSEHOLD_PASSWORD="<choose one>"
$env:DEMO_ADMIN_PASSWORD="<choose one>"
```

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `3306` / `energy_trading` | MySQL location |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / *(empty)* | MySQL credentials |
| `DB_URL` | built from the above | Full JDBC URL override |
| `JPA_DDL_AUTO` | `validate` | Hibernate schema mode (`update` only for throwaway DBs) |
| `SEED_DEMO_DATA` | `true` | Seed demo households when `users` is empty |
| `DEMO_HOUSEHOLD_PASSWORD` / `DEMO_ADMIN_PASSWORD` | *(random)* | Demo account passwords (stored as BCrypt hashes) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8082,http://127.0.0.1:8082` | Allowed browser origins |
| `PRIVACY_PSEUDONYM_KEY` | *(none: required)* | HMAC key for pseudonyms (at least 32 characters); the app refuses to start without it |
| `METER_*`, `ANOMALY_*` | see [Batch 2](#batch-2-validation-privacy-and-anomaly-detection) | Validation and anomaly thresholds |

### 3. Database Setup
[`database/schema.sql`](database/schema.sql) is the authoritative, versioned schema. The backend runs
Hibernate in `validate` mode, so it refuses to start if the entities and the database disagree.
Apply the schema once to a new database:
```powershell
# Local MySQL client
mysql -u root -p < database/schema.sql

# Or a MySQL Docker container (example container name: mysql-energy)
Get-Content database/schema.sql | docker exec -i mysql-energy sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```
The script uses `CREATE ... IF NOT EXISTS` and never drops data.

> **Existing databases created by an earlier `ddl-auto=update` run** have MySQL `ENUM` columns
> and no CHECK/unique-sequence constraints, so schema validation fails with
> `wrong column type ... found [enum], expecting [varchar(30)]`. Upgrade them in place, without
> losing data, using migration
> [`V001`](database/migrations/V001__align_hibernate_generated_schema.sql). See
> [`database/migrations/README.md`](database/migrations/README.md) for the backup, rehearsal and
> apply procedure. Do not switch to `JPA_DDL_AUTO=update` as a workaround.

On first start against an empty `users` table, `DataInitializer` seeds the demo households
`HH-001` (seller), `HH-002` (buyer) and `HH-ADMIN`, two meter readings and one audit entry.

### 4. Running Backend (Spring Boot)
```powershell
mvn -f backend/pom.xml spring-boot:run
```
- Health check: `http://localhost:8082/api/health`. It returns HTTP 200 with `"status":"UP"` and
  `"database":{"status":"UP","product":"MySQL",...}`, or HTTP 503 with `"status":"DOWN"` if the
  database is unreachable.

### 5. Running Local Blockchain (Hardhat)
```powershell
cd blockchain
npm install
npm run node        # JSON-RPC at http://127.0.0.1:8545, chain ID 31337
```
`contracts/`, `scripts/` and `test/` are intentionally empty until Batch 3, so `npm run compile`
prints `Nothing to compile`, `npm test` reports `0 passing`, and `npm run deploy:local` will fail
until `scripts/deploy.js` is added in Batch 3.

---

## Batch 2: Validation, Privacy and Anomaly Detection

The design and its limitations are described in
[`docs/privacy-and-security.md`](docs/privacy-and-security.md).

### Authentication and roles
HTTP Basic authentication. The username is the household code (e.g. `HH-001`) and the password
is checked against the stored BCrypt hash.

| Endpoint | Access |
|---|---|
| `GET /api/health` | public |
| `GET /api/privacy/public/households` | public: pseudonym and validated sellable surplus only |
| `POST /api/meter-readings` | `HOUSEHOLD`, own meter only. 201 VALID / 202 FLAGGED / 422 REJECTED |
| `GET /api/meter-readings/{readingId}/integrity` | owning `HOUSEHOLD`, `ADMIN`, `AUDITOR`. MATCH / MISMATCH / NO_STORED_DIGEST |
| `GET /api/me` | any authenticated account (own profile) |
| `GET /api/privacy/households/{code}` | owning `HOUSEHOLD` or `ADMIN` |
| `GET /api/households/{code}/meter-readings` | owning `HOUSEHOLD` or `ADMIN` (paged: `page`, `size` up to 100) |
| `GET /api/households` | `ADMIN` |
| `GET /api/anomalies` | `ADMIN`, `AUDITOR` (paged) |
| `PATCH /api/anomalies/{id}/review` | `ADMIN`; body `{"reviewStatus":"UNDER_REVIEW"\|"RESOLVED"\|"DISMISSED"}` |
| `GET /api/audit-logs` | `ADMIN`, `AUDITOR` (paged) |
| `POST /api/anomaly-checks/offer` | `HOUSEHOLD`; evaluates `{"quantityKwh":..,"pricePerKwh":..}`; no offer is created |

Unauthenticated requests get 401 and forbidden ones get 403, both as JSON `ApiError`
bodies. Ownership is enforced on the server, and denials are audited.

Example reading submission:
```powershell
curl.exe -u HH-001:<password> -H "Content-Type: application/json" `
  -d '{\"readingId\":\"MR-HH001-0002\",\"sequenceNo\":2,\"energyGeneratedKwh\":9.25,\"energyConsumedKwh\":3.5,\"readingTime\":\"2026-10-17T09:00:00\"}' `
  http://localhost:8082/api/meter-readings
```

### Meter-reading validation (`SmartMeterValidationService`)
Energy values are handled as `BigDecimal` with at most 3 decimal places. More precise values are
rejected, never rounded. `available = generated - consumed`, and only a positive balance from a
**VALID** reading becomes sellable surplus, so consumed energy is never treated as available.

| Rule code | Outcome | Condition |
|---|---|---|
| `MISSING_REQUIRED_FIELD` | REJECTED | readingId, sequenceNo, readingTime, energy values or household missing |
| `INVALID_IDENTIFIER` | REJECTED | malformed readingId, or sequenceNo of 0 or less |
| `INVALID_QUANTITY` / `INVALID_PRECISION` | REJECTED | negative energy / more than `max-scale` decimals |
| `IMPLAUSIBLE_READING` | REJECTED | above `METER_MAX_GENERATED_KWH` / `METER_MAX_CONSUMED_KWH` (25 kWh) |
| `FUTURE_TIMESTAMP` / `STALE_TIMESTAMP` | REJECTED | more than 5 min in the future / older than 7 days / not later than the previous reading |
| `DUPLICATE_READING_ID` / `DUPLICATE_SEQUENCE` / `STALE_SEQUENCE` | REJECTED | reused reading ID / reused household sequence / lower than the latest sequence (replay) |
| `CONCURRENT_DUPLICATE` | REJECTED | a concurrent duplicate won the race; the DB unique constraint caught it |
| `SEQUENCE_GAP` | FLAGGED | more than `METER_MAX_SEQUENCE_GAP` (0) missing sequence numbers |
| `SUDDEN_READING_CHANGE` | FLAGGED | change from the last VALID reading > 20 kWh, or > 300% (relative check skipped below a 1 kWh baseline) |
| `EXCESSIVE_ACTIVITY` | FLAGGED | more than 12 readings per household per hour |

REJECTED readings are not stored. FLAGGED readings are stored for review but do **not** update the
household's surplus. First readings, and history older than `METER_CONTINUITY_WINDOW` (24h), skip
the continuity checks, and the response explains why in its `notes` field.

### Anomaly detection (`AnomalyDetectionService`)
The same rule engine also evaluates offers: `OVERSELLING` (quantity above validated surplus,
REJECTED), `INVALID_PRICE` (price of 0 or less, REJECTED) and `SUSPICIOUS_PRICE` (above
`ANOMALY_MAX_PRICE_PER_KWH` = 15.00, FLAGGED for review, which is not an accusation of fraud). Each
finding is stored in `anomaly_logs` with its rule code, severity, explanation, timestamp, review
status and the household **pseudonym**, never a name, e-mail or household code. Each rule's
outcome is fixed by its code, so no schema change was needed. All thresholds are validated at
startup; see `application.properties` and [`.env.example`](.env.example).

---

## Verification

```powershell
# Backend unit/slice tests (in-memory H2; no MySQL needed)
mvn -f backend/pom.xml clean test

# Optional: real-MySQL integration test (schema.sql must be applied; uses DB_* variables)
$env:RUN_MYSQL_IT="true"
mvn -f backend/pom.xml test -Dtest=MySqlIntegrationTests

# Blockchain toolchain
cd blockchain
npm test
npm run compile
```
The H2 tests cover Batch 2 validation rules, anomaly rules and thresholds, canonical digests,
pseudonyms, the role/ownership policy through the real security filter chain, database-constraint
handling for concurrent duplicates, and integrity checks. They also cover context startup, the health endpoint, seed data and password hashing,
CORS and security rules, every repository's derived queries, entity relationships, unique
reading/trade/household identifiers, the per-household sequence-number constraint, and
bean-validation rules. `MySqlIntegrationTests` is skipped unless `RUN_MYSQL_IT=true`. It checks
that the datasource really connects to MySQL and that Hibernate validation passes against the schema.

---

## Implementation Batches
- **Batch 1 (Completed)**: Project Scaffolding, Domain Model, Database Entities, Repositories, Health Endpoint & Hardhat Setup.
- **Batch 2 (Completed, pending review)**: Smart-Meter Validation Service, Privacy Engine (canonical SHA-256 digests, HMAC pseudonyms, role-based access), and Rule-based Anomaly Detection.
- **Batch 3**: Solidity Smart Contract (`EnergyTrading.sol`), Deployment Scripts, Web3j Blockchain Connector, and Trading API.
- **Batch 4**: Frontend Dashboard (Bootstrap/HTML/JS) and End-to-End Scenario Verification (Scenarios A through G).

> Security note: from Batch 2 all non-public endpoints require authentication. Databases seeded by
> the first checkpoint (`f46267a`) have demo passwords that are visible in Git history. Reset them
> before exposing the API beyond localhost (see `docs/privacy-and-security.md`, section 7).
