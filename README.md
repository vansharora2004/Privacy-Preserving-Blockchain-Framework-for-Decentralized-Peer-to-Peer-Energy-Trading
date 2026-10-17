# Privacy-Preserving and Trust-Aware Blockchain Framework for Decentralized Peer-to-Peer Energy Trading

A prototype peer-to-peer (P2P) energy trading framework combining smart-meter validation, privacy-preserving data handling, rule-based anomaly detection, and a local EVM blockchain smart contract.

---

## Run the Demo (one command)

Prerequisites: JDK 21+, Maven 3.9+, Node.js 18+ (with npm), and MySQL 8 with the schema applied
(see [Database Setup](#3-database-setup); the Docker container `mysql-energy` is assumed below).

```powershell
docker start mysql-energy                # if MySQL runs in Docker
./scripts/start-demo.ps1                 # Hardhat node + contract + backend
# open http://localhost:8082/
```
[`scripts/start-demo.ps1`](scripts/start-demo.ps1):
1. checks Java, Maven, Node.js and the MySQL port;
2. opens a **Hardhat node** in its own window (close that window to simulate a blockchain outage);
3. reuses the contract in `blockchain/deployments/localhost.json` if it is still live on the node,
   otherwise deploys a new one with Hardhat dev account #1 as the recorder;
4. builds the backend if needed and runs it in the current window (**Ctrl+C** stops it cleanly).

Options: `-DbName <name>` (default `energy_trading`), `-Rebuild` (rebuild after code changes),
`-NoBlockchain` (marketplace without a chain; trades stay PENDING).

**Secrets are never printed.** The MySQL password comes from `DB_PASSWORD`, or, if that is unset,
from the Docker container's `MYSQL_ROOT_PASSWORD`, or from a prompt. `PRIVACY_PSEUDONYM_KEY` is
used if set; otherwise a random key is generated **once** and saved as a user environment variable,
so pseudonyms stay stable between runs. The backend signs with Hardhat's publicly known development
account #1: it holds no real value, and the helper refuses to run against any chain other than a
local Hardhat node (chain ID 31337) with the default development accounts.

**Demo accounts.** Sign in with the household code as the username:

| Account | Role | Password |
|---|---|---|
| `HH-001` (SolarHouse Alpha) | household, seller | the household demo password |
| `HH-002` (GreenVilla Beta) | household, buyer | the household demo password |
| `HH-ADMIN` (Grid Overseer) | administrator | the admin demo password |

Passwords are not stored in this repository.
- **New, empty database:** before the first start, set `$env:DEMO_HOUSEHOLD_PASSWORD` and
  `$env:DEMO_ADMIN_PASSWORD`. They are seeded once as BCrypt hashes.
- **Existing local `energy_trading` database:** its passwords were rotated. They are in the user
  environment variables `ENERGY_TRADING_HH001_PASSWORD`, `ENERGY_TRADING_HH002_PASSWORD` and
  `ENERGY_TRADING_HHADMIN_PASSWORD`; read one with
  `[Environment]::GetEnvironmentVariable('ENERGY_TRADING_HH001_PASSWORD','User')`.

A step-by-step presentation script is in [Batch 4: Demo walkthrough](#demo-walkthrough).

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
│       │       ├── application.properties
│       │       └── static/   # Dashboard: index.html, css/app.css, js/*.js (Batch 4)
│       └── test/             # JUnit, @DataJpaTest & MockMvc tests (+ opt-in MySQL IT)
├── scripts/start-demo.ps1    # One-command local demo (Batch 4)
├── blockchain/               # Hardhat EVM local development environment
│   ├── contracts/            # EnergyTrading.sol (Batch 3)
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

### 5. Running the Local Blockchain (Hardhat 2) and Connecting the Backend
```powershell
cd blockchain
npm install
npm run compile                 # solc 0.8.24 -> artifacts/contracts/EnergyTrading.sol/EnergyTrading.json (ABI)
npm test                        # contract tests on the in-process Hardhat network
npm run node                    # terminal 1: JSON-RPC at http://127.0.0.1:8545, chain ID 31337
```
In a second terminal, deploy the contract. `RECORDER_ADDRESS` is the account the backend will sign
with; it defaults to the deployer. Using a separate dev account (e.g. Hardhat account #1, listed by
`npm run node`) keeps the contract owner and the recorder apart:
```powershell
cd blockchain
$env:RECORDER_ADDRESS = "<address of Hardhat account #1>"
npm run deploy:local
# -> "EnergyTrading EnergyTrading/2 deployed to 0x... on localhost (chainId 31337)"
# -> "BLOCKCHAIN_DEPLOYMENT_ID=0x..."
```
The address, chain ID, deployer, recorder, deployment transaction, **deployment ID** and genesis
hash are written to `blockchain/deployments/localhost.json` (git-ignored). A fresh `npm run node`
always starts an empty chain, so redeploy after every node restart. Hardhat then usually reuses the
**same contract address**, which is why the backend identifies a deployment by its deployment ID
(a value the contract derives at construction from the chain ID, its address, a random salt, the
previous block hash and the timestamp), not by its address. See
[Chain reset and recovery](#chain-reset-and-recovery-operator-procedures).

Then start the backend with the blockchain enabled:
```powershell
$env:BLOCKCHAIN_ENABLED = "true"
$env:CONTRACT_ADDRESS = (Get-Content blockchain/deployments/localhost.json | ConvertFrom-Json).address
$env:BLOCKCHAIN_DEPLOYMENT_ID = (Get-Content blockchain/deployments/localhost.json | ConvertFrom-Json).deploymentId
$env:BLOCKCHAIN_SIGNER_PRIVATE_KEY = "<private key of the recorder account, shown by npm run node>"
mvn -f backend/pom.xml spring-boot:run
```
`GET /api/blockchain/status` (ADMIN/AUDITOR) reports whether the chain ID, contract code, contract
version, deployment ID and recorder authorization all check out, and shows the genesis hash. Use Hardhat dev accounts only. Never use a real
wallet, and never commit a private key.

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
| `GET /api/privacy/public/households` | public: household pseudonyms only (no energy figures) |
| `GET /api/privacy/public/market-summary` | public: aggregate open-offer figures; withheld when fewer than 3 sellers have open offers |
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

## Batch 3: Marketplace, Smart Contract and Blockchain Settlement

### Trade lifecycle
```
              +--> REJECTED (hard rule; nothing reserved)
request ------+
              +--> FLAGGED --admin approve--> PENDING
              |       \--admin reject / review timeout--> REJECTED / EXPIRED  (released)
              +--> PENDING --buyer/admin cancel--> CANCELLED                  (released)
                      |  \--reservation timeout, never broadcast--> EXPIRED  (released)
                      v
                  SUBMITTED --verified receipt + TradeRecorded event--> CONFIRMED  (seller paid)
                      |  \--reverted and trade not recorded--> FAILED          (released)
                      +--tx unknown after grace, nonce unused--> PENDING (re-sent with the SAME nonce)
                      +--tx unknown after grace, nonce consumed--> PENDING (new nonce; max attempts -> FAILED)
                      \--deployment changed / evidence inconsistent--> NEEDS_REVIEW  (funds stay reserved)

NEEDS_REVIEW --admin resolve-confirmed (verified evidence)--> CONFIRMED
             --admin resolve-failed (evidence shows not recorded)--> FAILED   (released)
             --admin re-anchor (prior deployment retired)--> PENDING --> CONFIRMED on the current deployment
```
`NEEDS_REVIEW` is a holding state: no scheduled job submits, expires, confirms or releases it. The
buyer and seller see "Held for administrator review"; administrators see the technical reason, the
attempt history and an open alert.
- **Offers** are backed by the seller's latest VALID meter reading, which must be at most 24h old.
  Available energy is `max(0, generated - consumed)` minus energy already committed to offers from
  that reading; cancelled and expired offers return their unsold remainder. Offers expire
  (`expiresInMinutes`, 5 min to 24 h, default 60 min) and can be cancelled by the seller or an admin.
- **Purchases** take price, seller and totals from the server-side offer. The trade locks the offer
  row (`SELECT ... FOR UPDATE`), so concurrent purchases cannot oversell. It reserves the energy,
  escrows the buyer's funds, and pays the seller only on CONFIRMED. Any release returns both.
- **Rules:** quantity, price and overselling checks, `SELF_TRADE` (rejected), `INSUFFICIENT_FUNDS`
  (rejected), `UNVERIFIED_SOURCE_READING` (rejected), `SUSPICIOUS_PRICE` and `HIGH_TRADE_FREQUENCY`
  (more than 10 trades per hour, counted inside the locked purchase transaction; FLAGGED for admin
  review). Every finding goes to the anomaly log, and every request to the audit log.
- **Idempotency:** `POST /api/trades` requires an `Idempotency-Key` header (8-64 characters). A
  retry with the same key returns the original trade (HTTP 200); reusing a key for a different
  request returns 409. `POST /api/offers` accepts the same header optionally.
- **History:** every transition is stored in `trade_events` and returned as `history` with role
  labels (BUYER, SELLER, ADMIN, SYSTEM, BLOCKCHAIN), never household codes.
- **Housekeeping:** a scheduled job (every 30 s; `MARKET_SCHEDULER_ENABLED`) expires offers,
  releases timed-out reservations (PENDING 15 min, FLAGGED 24 h), retries PENDING submissions and
  reconciles SUBMITTED trades. A PENDING reservation that already has a blockchain attempt is
  expired only if no attempt is still known to the node; attempts on another deployment send it to
  NEEDS_REVIEW instead. A second job (`MARKET_ANCHOR_AUDIT_INTERVAL_MS`, default 5 min) runs the
  anchor audit.
- **Offer cancellation with trades in flight:** cancelling an offer releases its PENDING and
  FLAGGED reservations ("offer cancelled"). A SUBMITTED trade is not released, because its
  transaction may still be mined; it continues to its verified outcome.

### Marketplace endpoints

| Endpoint | Access |
|---|---|
| `POST /api/offers` | `HOUSEHOLD`. 201 created (PASSED/FLAGGED), 200 idempotent replay, 422 REJECTED |
| `GET /api/offers` | any authenticated account: open offers, seller shown as pseudonym |
| `GET /api/offers/mine`, `GET /api/offers/availability` | `HOUSEHOLD` (own) |
| `POST /api/offers/{offerId}/cancel` | the offer's seller or `ADMIN` |
| `POST /api/trades` (header `Idempotency-Key`) | `HOUSEHOLD`. 201 CONFIRMED, 202 PENDING/FLAGGED/SUBMITTED, 200 replay/FAILED, 422 REJECTED, 409 offer not open / key reuse |
| `GET /api/trades/mine` | `HOUSEHOLD` (as buyer or seller) |
| `GET /api/trades/{tradeId}`, `GET /api/trades/{tradeId}/verification` | buyer, seller, `ADMIN`, `AUDITOR` |
| `POST /api/trades/{tradeId}/cancel` | the buyer or `ADMIN` (PENDING/FLAGGED only) |
| `POST /api/trades/{tradeId}/approve`, `/reject`, `/reconcile` | `ADMIN` |
| `POST /api/trades/{tradeId}/resolve-confirmed`, `/resolve-failed`, `/re-anchor` | `ADMIN`; body `{"reason":"10-200 chars","priorDeploymentRetired":bool}`; evidence-gated, audited, 409 when the evidence or state does not allow it |
| `GET /api/trades/{tradeId}/attempts` | `ADMIN`, `AUDITOR`: every signed transaction, including superseded ones |
| `GET /api/blockchain/alerts` | `ADMIN`, `AUDITOR`: open, de-duplicated alerts (paged) |
| `POST /api/blockchain/anchor-audit` | `ADMIN`: run the anchor audit now |
| `GET /api/trades`, `GET /api/blockchain/status` | `ADMIN`, `AUDITOR` |
| `GET /api/privacy/public/trades` | public: confirmed trades as quantity, price, tx hash, block and time only (no pseudonyms) |

An unknown trade or offer ID returns 404, while an existing one the caller may not access returns
403, so the status reveals whether an ID exists. This is kept deliberately (review finding L5):
IDs are random UUIDs that cannot be enumerated, the 403 body reveals nothing about the trade, and
every denial is audited.

### Smart contract (`blockchain/contracts/EnergyTrading.sol`)
- `recordTrade(tradeKey, sellerRef, buyerRef, quantityWh, pricePaisePerKwh, totalPaise, recordDigest)`:
  authorized recorders only. Rejects a reused trade key (`TradeAlreadyRecorded`), zero
  keys/parties/digest, identical parties, out-of-range quantity or price, and a total that differs
  from `round_half_up(quantityWh x pricePaisePerKwh / 1000)`. Emits `TradeRecorded`.
- `settleTrade` (Recorded to Settled) and `voidTrade(reasonCode)` (Recorded to Voided), both
  recorder-only, with `TradeSettled` / `TradeVoided`. The backend uses only `recordTrade` today.
- Owner-only `setRecorder` / `transferOwnership`; views `isRecorded`, `getTrade`, `isRecorder`,
  `VERSION` (`EnergyTrading/2`) and `deploymentId`.
- **Deployment identity:** the constructor takes `(initialRecorder, deploymentSalt)`, rejects a zero
  salt, and stores `deploymentId = keccak256(chainid, address, salt, previous blockhash, timestamp)`
  (emitted as `Deployed`). A reset chain that reuses the address yields a different ID.
- **Valid anchors:** a trade is anchored when its record is `Recorded` or `Settled` and matches the
  database. `Voided` is never a valid anchor.
- **Units:** 1 kWh = 1000 Wh; 1 rupee = 100 paise. Example: 2.5 kWh at Rs 6.50 = 2500 Wh at
  650 paise/kWh = 1625 paise (Rs 16.25).
- **Privacy:** only a hashed trade key, per-trade keyed party references, amounts and a record
  digest go on-chain (see `docs/privacy-and-security.md`, section 7).

### Blockchain connector (Web3j 4.12.3)
The connector uses raw ABI encoding and locally signed EIP-155 transactions. Every submission follows
this sequence, inside a submission lock (`BLOCKCHAIN_SUBMIT_LOCK_WAIT`, default 2 s; a purchase
that cannot get it within that time stays PENDING and is submitted by the scheduler):
1. Hold the **signer lease** (see below).
2. Verify the network **afresh**: chain ID, contract code, `VERSION()`, `deploymentId()` against
   `BLOCKCHAIN_DEPLOYMENT_ID`, the genesis hash, and the signer's recorder authorization. Only the
   status endpoint and the scheduler's "is the network up" check use a short cache.
3. If earlier attempts for the trade were made on another deployment, hold it as NEEDS_REVIEW
   (unless an administrator approved a re-anchor to this deployment).
4. If the contract already records the trade, adopt that record only with full evidence (see below).
5. Choose the nonce: the trade's **pinned nonce** if an earlier attempt's nonce is still unused,
   otherwise the next pending nonce. Sign.
6. In one database transaction, re-validate the lease and **persist the attempt (hash, nonce,
   deployment, lease token) and the trade as SUBMITTED before broadcasting**. Every attempt is kept
   in `trade_submission_attempts`.
7. Broadcast; await the receipt outside the lock.

**Confirmation requires evidence**, never a receipt status alone: the receipt must be successful,
addressed to the configured contract, and contain a `TradeRecorded` event for this trade key; the
contract must hold the trade as Recorded or Settled; the on-chain data must match the database
record; and the deployment must be unchanged. A reverted receipt gives FAILED only when the trade is
not recorded. Any inconsistency gives NEEDS_REVIEW.

**Resubmission rules** (this replaces the earlier "never blindly resubmitted" wording):
- **No response from the node:** the outcome is *uncertain*; the trade stays SUBMITTED and is
  reconciled later. It is never treated as a failure.
- **Reconciliation** checks, in order: deployment and genesis unchanged (else NEEDS_REVIEW), the
  receipt by the stored hash, the contract state, and the node's pool. A transaction is resubmitted
  only once the node has not known it for `BLOCKCHAIN_UNKNOWN_TX_GRACE` and the trade is absent
  from the contract. Then:
  - if the signer's latest (mined) nonce has **not** passed the stored nonce, the trade is re-sent
    with the **same nonce**, so the old and the new transaction cannot both be mined;
  - if that nonce was consumed by another transaction, the old transaction can no longer be mined
    and a fresh nonce is used, up to `BLOCKCHAIN_MAX_SUBMISSION_ATTEMPTS`, after which the trade is
    FAILED ("Blockchain submission failed after N attempts").
- **Duplicate trade keys** are rejected by the contract, which is the final guard: even an
  unforeseen double broadcast can record a trade at most once per deployment.
- **Limitation:** "the node does not know the transaction" is evidence from one node. Resubmission
  is safe here because of the same-nonce rule and the contract's duplicate guard, not because the
  absence is proof.

**Signer coordination across instances.** Submissions share a signer nonce, so only one backend
instance may sign at a time. The instance must hold a row in `blockchain_signer_lease`
(`BLOCKCHAIN_SIGNER_LEASE_TTL`, default 30 s, judged by the database clock). Taking over an expired
lease increments a **fencing token**; the write-ahead step re-checks the token inside the database
transaction, and an instance whose lease is about to expire abandons the signed transaction instead
of broadcasting it. An instance without the lease does not sign or reconcile and raises a
`SIGNER_LEASE_UNAVAILABLE` alert. A cleanly stopped instance releases its lease; a killed instance
blocks signing until its lease expires (at most one TTL). Different signers can run in parallel.

The database and the chain are separate systems and are **not** updated atomically; this workflow,
the attempt history and NEEDS_REVIEW are what keep them consistent.

**Anchor audit.** `POST /api/blockchain/anchor-audit` (and the scheduled job) re-checks CONFIRMED
trades, least recently checked first, in batches of `BLOCKCHAIN_ANCHOR_AUDIT_BATCH_SIZE` (200).
A trade anchored on another deployment, missing on-chain, Voided, different from the database, or
whose stored receipt does not show `TradeRecorded` raises `ANCHOR_MISSING` or `ANCHOR_INVALID`.
Confirmed trades from before V003 get their deployment recorded on the first successful check. The
audit also completes administrator-approved re-anchors that were fenced, busy or left without a
receipt. `GET /api/trades/{id}/verification` returns MATCH, MISMATCH, UNVERIFIABLE, VOIDED,
NOT_ON_CHAIN, DEPLOYMENT_CHANGED, UNAVAILABLE or BLOCKCHAIN_DISABLED.

| Variable | Default | Purpose |
|---|---|---|
| `BLOCKCHAIN_ENABLED` | `false` | Enables the Web3j connector |
| `BLOCKCHAIN_RPC_URL` / `BLOCKCHAIN_CHAIN_ID` | `http://127.0.0.1:8545` / `31337` | Node and expected chain |
| `CONTRACT_ADDRESS` | *(required when enabled)* | From `blockchain/deployments/localhost.json` |
| `BLOCKCHAIN_DEPLOYMENT_ID` | *(required when enabled)* | `deploymentId` from the same file; bytes32 hex |
| `BLOCKCHAIN_SIGNER_PRIVATE_KEY` | *(required when enabled; secret)* | Authorized recorder key |
| `BLOCKCHAIN_RECEIPT_TIMEOUT`, `BLOCKCHAIN_MAX_SUBMISSION_ATTEMPTS`, `BLOCKCHAIN_UNKNOWN_TX_GRACE` | `15s`, `3`, `60s` | Settlement tuning |
| `BLOCKCHAIN_SIGNER_LEASE_TTL`, `BLOCKCHAIN_SUBMIT_LOCK_WAIT`, `BLOCKCHAIN_ANCHOR_AUDIT_BATCH_SIZE` | `30s`, `2s`, `200` | Signer lease, lock wait, audit batch |
| `MARKET_*` | see `.env.example` | Offer lifetimes, reservation timeouts, scheduler, `MARKET_ANCHOR_AUDIT_INTERVAL_MS` |

Missing or invalid settings stop startup with a message that names the variable, never its value.

### Chain reset and recovery (operator procedures)
**Chain reset or redeployment** (e.g. restarting `npm run node`):
1. The backend detects it on the next decision (deployment ID or genesis hash differs, or the
   contract is missing). It stops signing, raises `NETWORK_VERIFICATION_FAILED`, and leaves every
   trade untouched; SUBMITTED trades stay SUBMITTED and new purchases stay PENDING.
2. Redeploy, review the new deployment, then set `CONTRACT_ADDRESS` and `BLOCKCHAIN_DEPLOYMENT_ID`
   from `blockchain/deployments/localhost.json` and restart. If the old instance was killed rather
   than stopped, signing resumes once its signer lease expires (at most `BLOCKCHAIN_SIGNER_LEASE_TTL`).
3. Trades submitted on the old deployment move to NEEDS_REVIEW on reconciliation: a missing
   transaction on the new chain proves nothing about the old one. Trades never broadcast are
   submitted normally.
4. Run `POST /api/blockchain/anchor-audit` and read `GET /api/blockchain/alerts`: CONFIRMED trades
   anchored on the old deployment show `ANCHOR_MISSING`.
5. For each NEEDS_REVIEW trade, read `GET /api/trades/{id}/attempts`, then either
   `re-anchor` (record it on the new deployment; the trade is paid on confirmation) or
   `resolve-failed` (release the funds). Both require `priorDeploymentRetired=true`, which is the
   administrator's attestation that the old deployment can no longer record the trade.
6. For each CONFIRMED trade with `ANCHOR_MISSING`, `re-anchor` records it again on the new
   deployment **without moving money**; the anchor audit completes approvals that were delayed.

**Unknown outcome** (SUBMITTED for a long time): run `/reconcile`. It confirms from evidence, keeps
waiting while the node holds the transaction, or resubmits under the rules above.

**Unresolved trades:** every NEEDS_REVIEW trade has an open `TRADE_NEEDS_REVIEW` alert and stays
reserved until an administrator resolves it. `resolve-confirmed` needs verified on-chain evidence
on the current deployment; `resolve-failed` needs evidence that the trade is not recorded there
(and the attestation if earlier attempts were on another deployment); repeating an action returns
409. Each resolution is audited (`TRADE_RESOLVE_CONFIRMED`, `TRADE_RESOLVE_FAILED`,
`TRADE_REANCHOR`) with the administrator, reason and deployment. Recovery fails closed (409) when
the blockchain cannot be verified.

### Database (schema v3, migrations V002 and V003)
- `energy_offers`: adds `expires_at`, `client_request_id` and the unique key `(seller_id, client_request_id)`.
- `trade_transactions`: adds `client_request_id` and the unique key `(buyer_id, client_request_id)`,
  plus `updated_at`, `tx_nonce`, `submitted_at`, `submission_attempts`, `chain_id`,
  `contract_address` and `record_digest`; `tx_hash` becomes unique.
- New table `trade_events` holds the state-transition history.

V003 (`V003__deployment_identity_recovery_and_signer_lease.sql`) adds to `trade_transactions` the
deployment ID, genesis hash, stored party references (`seller_ref`, `buyer_ref`), `privacy_key_id`,
`review_reason`, `reanchor_deployment_id` and `anchor_checked_at`, and creates
`trade_submission_attempts`, `blockchain_alerts` and `blockchain_signer_lease`. `NEEDS_REVIEW` needs
no schema change.

Apply V002 to v1 databases and then V003 to v2 databases (see `database/migrations/README.md`).
The backend validates the schema at startup, so it will not start against a v2 database. New `TradeStatus` values (SUBMITTED, FAILED, CANCELLED,
EXPIRED) and `OfferStatus.EXPIRED` need no schema change, because status columns are `VARCHAR(30)`.

---

## Batch 4: Dashboard and Demo

The dashboard is a static single-page application served by Spring Boot at
`http://localhost:8082/`. It uses Bootstrap 5.3 (served locally from a WebJar, so it works offline)
and plain JavaScript modules, and it calls only the REST API described above.

| Role | Pages |
|---|---|
| Visitor (not signed in) | Sign-in page with the public market summary, recent confirmed trades and household pseudonyms; **Public view** |
| Household | **Overview** (balance, energy you can offer, surplus/deficit, recent trades) · **Smart meter** (submit readings, see VALID / FLAGGED / REJECTED with the rule that fired, replay test, integrity check) · **Marketplace** (open offers, buy, create an offer with an anomaly pre-check, cancel own offers) · **My trades** (status, transaction hash, block, history, on-chain verification) · **Privacy center** (own private profile next to what the public sees) |
| Administrator | **Overview** (counts, trades needing attention, blockchain health) · **Trades** (approve / reject flagged trades, reconcile, cancel, resolve NEEDS_REVIEW, re-anchor, submission attempts) · **Offers** (cancel) · **Households** (private profiles) · **Anomalies** (review status) · **Blockchain** (verification, alerts, anchor audit) · **Audit log** |
| Auditor | The administrator pages read-only, without private household profiles |

**What the dashboard shows** comes from the backend; nothing is inferred:
- A trade is shown as CONFIRMED only when the backend says so, with the real transaction hash and
  block number.
- PENDING and SUBMITTED trades carry the backend's explanation (for example "queued for blockchain
  submission" or "outcome not yet known") and refresh automatically.
- Business outcomes (REJECTED, FLAGGED) are shown with every rule code and message.

**Security and privacy in the browser:**
- Credentials are sent as HTTP Basic over the same origin. They are kept only in the tab's
  `sessionStorage`, cleared on sign-out or when the tab closes, and never logged.
- Server data is inserted as text, never as HTML.
- A strict Content-Security-Policy allows only same-origin scripts, styles and connections: no
  inline scripts and no CDNs.
- Dashboard requests send `X-Requested-With: XMLHttpRequest`. For those, a 401 omits the Basic
  challenge, so the browser does not pop up its own login dialog; other clients still receive it.
- Only static assets (`/`, `/index.html`, `/css/**`, `/js/**`, `/webjars/**`) are public. Every
  API endpoint keeps its authentication, roles and ownership checks. The UI hides actions a role
  cannot perform, but the server enforces them.

### Demo walkthrough
Start with `./scripts/start-demo.ps1`, open `http://localhost:8082/`, then:

1. **Public view (scenario F).** On the sign-in page, point out that visitors see only aggregate
   figures, pseudonyms and confirmed trades as quantity, price and transaction hash. Market figures
   are withheld while fewer than 3 sellers are active.
2. **Seller: valid reading.** Sign in as `HH-001` and open **Smart meter**. The form is pre-filled
   (8 kWh generated, 3 kWh consumed). **Submit reading** gives `VALID (HTTP 201)` with its SHA-256
   record digest.
3. **Replay (scenario C).** Click **Resubmit last reading (replay test)**: `REJECTED`,
   `DUPLICATE_READING_ID` / `DUPLICATE_SEQUENCE`.
4. **Manipulated reading (scenario B).** Change *Energy generated* to `50` and submit: `REJECTED`,
   `IMPLAUSIBLE_READING`; nothing is stored. **Check integrity** on a stored reading gives `MATCH`.
5. **Overselling (scenario D).** Open **Marketplace** ("You can offer up to 5 kWh"). Offer `100`
   kWh at `8.00`: `Offer REJECTED`, `OVERSELLING`.
6. **Abnormal price (scenario E).** Enter `1` kWh at `100` and click **Check first**:
   `Pre-check: FLAGGED`, `SUSPICIOUS_PRICE` (above Rs 15/kWh). Nothing is created.
7. **Create the offer.** `5` kWh at `8.00`, then **Create offer**: `PASSED`; it appears under
   *Your offers*.
8. **Buyer: valid trade (scenario A).** Sign out and sign in as `HH-002`. In **Marketplace**, buy
   `3` kWh from that offer: `Purchase CONFIRMED (HTTP 201)`, total Rs 24.00, with the real
   transaction hash and block number from the local chain.
9. **Duplicate request.** Click **Send the same request again (replay test)**. It reuses the same
   `Idempotency-Key`, and the original trade comes back (HTTP 200); nothing is charged twice.
10. **Verification.** Open **My trades** → **Details** → **Verify on blockchain**: `MATCH`
    against the contract. The history shows PENDING → SUBMITTED → CONFIRMED.
11. **Privacy and authorization (scenario F).**
    - **Privacy center** shows your private profile next to the public view.
    - Typing `#/admin` in the address bar returns you to your own dashboard.
    - The API itself refuses: `GET /api/households` returns 403 for a household.
12. **Administrator.** Sign in as `HH-ADMIN`.
    - **Anomalies** lists the rejected and flagged findings; change one to `UNDER_REVIEW`.
    - **Households** shows private profiles.
    - **Blockchain** shows "Connected and verified".
    - **Audit log** shows every request.
13. **Blockchain failure (scenario G).**
    - Close the *Hardhat node* window.
    - As `HH-002`, buy 1 kWh: `Purchase PENDING (HTTP 202)`, "queued for blockchain submission".
      It is never shown as confirmed, and the funds stay reserved.
    - As admin, **Blockchain** shows "Blockchain not usable" with a `NETWORK_VERIFICATION_FAILED`
      alert.
14. **Recovery.**
    - Stop the backend (Ctrl+C) and run `./scripts/start-demo.ps1` again. It starts a fresh node,
      sees that the old deployment is gone, and deploys a new contract.
    - The queued trade is submitted to the new chain and becomes CONFIRMED (or click **Reconcile
      with blockchain**).
    - **Run anchor audit**: trades confirmed on the old chain show `ANCHOR_MISSING`.
    - Open one and choose **Re-anchor on current chain**, giving a reason and the attestation. It is
      re-recorded without moving money, and **Verify on blockchain** gives `MATCH`.

**Troubleshooting:**
- **Port 8082 in use:** the backend is already running; stop it with Ctrl+C.
- **`Schema-validation` at startup:** apply `database/schema.sql` (new database) or the migrations
  (existing one).
- **Login fails on a new database:** `DEMO_*_PASSWORD` was not set before the first start. Create
  a fresh database, or rotate the passwords as described in `docs/privacy-and-security.md`.
- **Signing pauses for up to 30 s after a killed backend:** the old instance's signer lease must
  expire first. Ctrl+C avoids this.
- **The Hardhat node was restarted:** the chain is empty. Re-run the script, which redeploys, and
  follow step 14 for trades from the old chain.

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

# Optional: live tests against a running node with EnergyTrading deployed
# (needs BLOCKCHAIN_RPC_URL, CONTRACT_ADDRESS, BLOCKCHAIN_DEPLOYMENT_ID, BLOCKCHAIN_SIGNER_PRIVATE_KEY of the recorder)
$env:RUN_BLOCKCHAIN_IT="true"
mvn -f backend/pom.xml test -Dtest='Web3jBlockchainGatewayIT,LiveBlockchainTradeIT'
```
The `*IT` classes are not run by a plain `mvn test`. They need the live node and are skipped unless
`RUN_BLOCKCHAIN_IT=true`.

Batch 3 tests:
- `MarketplaceWorkflowTests`: offers from validated surplus, limits, expiry, cancellation,
  reservation, self-trade, funds, review, frequency, idempotency, reservation expiry, concurrent
  overselling and concurrent duplicate keys.
- `TradeBlockchainWorkflowTests`, against a deterministic fake chain: success with write-ahead hash
  persistence, revert, RPC failure, wrong chain or unauthorized signer, uncertain outcomes
  (mined / lost), node rejection with an attempt limit, adoption of an on-chain record, mismatch,
  pending transactions, concurrent submission and reconciliation.
- `MarketplaceApiTests`: roles, ownership, status codes, no private data in responses, and
  admin-only recovery endpoints.
- `BlockchainRecoveryTests` (remediation): deployment identity and chain reset at the same address,
  anchor audit, confirmation evidence (wrong target, missing or foreign event, Voided, tampered
  records), administrator recovery paths, re-anchoring without double payment, and resilience
  (de-duplicated alerts, a slow node, status details, offer cancellation with trades in flight).
- `SignerLeaseTests`: exclusive lease, takeover with fencing, competing and crashed instances.
- `KeyRotationTests`: stored party references survive a pseudonym-key change.
- `PublicPrivacyApiTests`: the exact fields of every public response and the k=3 market threshold.
- `DashboardIntegrationTests` (Batch 4): the dashboard and local Bootstrap assets load without
  authentication and with the CSP and referrer headers; no inline script, external origin or
  secret in the served files; every API endpoint still answers 401 without credentials; dashboard
  (XHR) 401s carry no Basic challenge.
- The browser walkthrough above (scenarios A-G) was also run end to end against MySQL and a Hardhat
  node with a headless browser during Batch 4. That harness is not part of the repository.
- The live `*IT` tests: real Web3j against Hardhat, including revert, duplicate and RPC-down cases.
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
- **Batch 2 (Completed)**: Smart-Meter Validation Service, Privacy Engine (canonical SHA-256 digests, HMAC pseudonyms, role-based access), and Rule-based Anomaly Detection.
- **Batch 3 (Completed; review findings remediated)**: Marketplace and trade workflow, `EnergyTrading.sol` with tests and deployment script, Web3j settlement with reconciliation, deployment identity, NEEDS_REVIEW recovery, signer lease and anchor audit.
- **Batch 4 (Completed)**: Frontend dashboard (Bootstrap 5, HTML, JavaScript modules) for households, administrators and auditors; one-command local demo (`scripts/start-demo.ps1`); end-to-end verification of scenarios A through G in the browser.

> Security note: from Batch 2 all non-public endpoints require authentication. Databases seeded by
> the first checkpoint (`f46267a`) have demo passwords that are visible in Git history. The local
> `energy_trading` accounts have been rotated; any other database seeded from that checkpoint must
> be rotated too (see `docs/privacy-and-security.md`, section 8).
