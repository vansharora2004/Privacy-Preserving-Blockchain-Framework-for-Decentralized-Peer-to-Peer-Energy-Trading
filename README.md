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
The H2 tests cover context startup, the health endpoint, seed data and password hashing,
CORS and security rules, every repository's derived queries, entity relationships, unique
reading/trade/household identifiers, the per-household sequence-number constraint, and
bean-validation rules. `MySqlIntegrationTests` is skipped unless `RUN_MYSQL_IT=true`. It checks
that the datasource really connects to MySQL and that Hibernate validation passes against the schema.

---

## Implementation Batches
- **Batch 1 (Completed)**: Project Scaffolding, Domain Model, Database Entities, Repositories, Health Endpoint & Hardhat Setup.
- **Batch 2 (Next)**: Smart-Meter Validation Service, Privacy Engine (SHA-256 Hashing & Role-based Access), and Rule-based Anomaly Detection.
- **Batch 3**: Solidity Smart Contract (`EnergyTrading.sol`), Deployment Scripts, Web3j Blockchain Connector, and Trading API.
- **Batch 4**: Frontend Dashboard (Bootstrap/HTML/JS) and End-to-End Scenario Verification (Scenarios A through G).

> Security note: in Batch 1 all `/api/**` endpoints are open (`permitAll`) for development;
> role-based access control is added in Batch 2.
