# Implementation Plan

## Project Title

**Privacy-Preserving and Trust-Aware Blockchain Framework for
Decentralized Peer-to-Peer Energy Trading**

## 1. Implementation Overview

Implement the project in small, testable stages. First make the backend
and database work, then implement validation and anomaly rules, then
connect the smart contract, and finally complete the frontend and
end-to-end demonstration.

### Technology choices

  -----------------------------------------------------------------------
  Area                                Technology
  ----------------------------------- -----------------------------------
  Runtime                             Java 21

  Backend                             Spring Boot, Spring Web, Spring
                                      Data JPA, Spring Security

  Build                               Maven

  Database                            MySQL

  Frontend                            HTML, CSS, JavaScript, Bootstrap

  Smart contract                      Solidity

  Local blockchain                    Hardhat local EVM network

  Java blockchain integration         Web3j

  Testing                             JUnit, Spring Boot Test, contract
                                      tests with the Hardhat toolchain
  -----------------------------------------------------------------------

Use compatible stable versions of dependencies and record them in the
project configuration.

## 2. Proposed Repository Layout

``` text
energy-trading-project/
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/energytrading/
│       │   │   ├── EnergyTradingApplication.java
│       │   │   ├── controller/
│       │   │   ├── service/
│       │   │   ├── model/
│       │   │   ├── repository/
│       │   │   ├── dto/
│       │   │   ├── security/
│       │   │   └── config/
│       │   └── resources/
│       │       ├── application.properties
│       │       ├── static/css/
│       │       ├── static/js/
│       │       └── templates/
│       └── test/
├── blockchain/
│   ├── contracts/EnergyTrading.sol
│   ├── scripts/
│   ├── test/
│   ├── hardhat.config.js
│   └── package.json
├── database/
│   └── schema.sql
├── docs/
│   ├── architecture.md
│   ├── problemstatement.md
│   └── implementation.md
└── README.md
```

## 3. Phase 1 --- Project Setup

1.  Install JDK 21, Maven, MySQL, Node.js/npm, and Git.
2.  Create a Spring Boot Maven project with Spring Web, Spring Data JPA,
    MySQL Driver, Validation, Spring Security, and test dependencies.
3.  Create a MySQL database such as `energy_trading`.
4.  Keep database username/password in environment variables or a local
    untracked configuration file.
5.  Configure the backend to start locally and verify a health endpoint.
6.  Initialize the Hardhat project in `blockchain/`.

**Deliverable:** A running Spring Boot application, a reachable MySQL
database, and a working local blockchain development environment.

## 4. Phase 2 --- Database and Domain Model

Create JPA entities and repositories for:

-   `User`: household identity, display alias, role, and password hash.
-   `MeterReading`: household reference, unique reading ID, sequence
    number, energy value, timestamp, and validation result.
-   `EnergyOffer`: seller, source reading, offered kWh, price per kWh,
    and offer status.
-   `TradeTransaction`: trade ID, buyer, seller, kWh, unit price, total
    amount, application status, transaction hash, and block number.
-   `AnomalyLog`: rule code, severity, explanation, associated
    reading/trade, timestamp, and review status.
-   `AuditLog`: actor, action, outcome, and timestamp.

Add database constraints for unique reading IDs, unique trade IDs,
required fields, and valid references. Store currency using decimal-safe
types; avoid binary floating-point arithmetic for financial
calculations. Use schema migrations or a versioned SQL schema rather
than relying on accidental schema changes.

**Deliverable:** Entities, repositories, initial schema, and seed data
for a few demo households.

## 5. Phase 3 --- Smart-Meter Validation

Implement `SmartMeterValidationService`.

Checks: 1. Required identifiers and household must exist. 2. Energy
quantities must be non-negative and within configured bounds. 3. Reading
IDs and sequence numbers must not be reused. 4. Readings should be
consistent with the prior accepted reading and configured meter rules.
5. Offered energy must not exceed the accepted available surplus.

Example test: - Accepted available surplus: 5 kWh. - Submitted reading
or offer: 50 kWh. - Expected result: reject the reading/offer, return an
explicit reason, and create an audit entry.

A simple simulated meter can support the demo. Be clear that software
consistency checks cannot independently establish the truth of physical
measurements.

**Deliverable:** Validation service, result DTOs, and unit tests for
valid, negative, implausible, and duplicate/replayed readings.

## 6. Phase 4 --- Privacy and Access Control

Implement `PrivacyService` and configure Spring Security.

-   Keep names, detailed readings, credentials, and role assignments
    off-chain.
-   Use a stable pseudonymous household code in public transaction
    views.
-   Return only necessary fields in public DTOs.
-   Protect private endpoints with server-side role checks.
-   Hash selected canonical records with a standard digest such as
    SHA-256 when an integrity check is needed.
-   Recompute the digest from the same canonical representation before
    comparing it with the stored digest.

Do not describe hashing or pseudonyms as guaranteed anonymity. Avoid
storing personal data in contract events or public transaction input.

**Deliverable:** Public and authorized data endpoints, authorization
tests, and an integrity-check demonstration.

## 7. Phase 5 --- Rule-Based Anomaly Detection

Implement `AnomalyDetectionService` with configurable rules.

  -----------------------------------------------------------------------
  Rule                    Example condition       Recommended action
  ----------------------- ----------------------- -----------------------
  Quantity exceeds        Offer is greater than   Reject
  surplus                 accepted available kWh  

  Negative quantity or    Quantity or price is    Reject
  price                   below zero; quantity    
                          and price must meet     
                          business minimums       

  Duplicate identifier    Reading/trade ID        Reject
                          already exists          

  Out-of-range price      Price exceeds           Flag or reject
                          configured maximum,     according to policy
                          e.g. ₹15/kWh            

  High trade frequency    More than configured    Flag for review
                          trades within a time    
                          window                  

  Unusual reading jump    Reading change exceeds  Reject or flag for
                          configured plausibility review
                          limits                  
  -----------------------------------------------------------------------

Each result should include a rule code, severity, explanation, and
recommended action. Hard failures must block trade submission. A
suspicious but potentially legitimate trade can be marked `FLAGGED` for
review instead of automatically being called fraud.

Example: - Price: ₹100/kWh. - Configured maximum: ₹15/kWh. - Expected
result: a price-threshold alert and the configured flag/reject behavior.

**Deliverable:** Rule engine/service, anomaly logs, configurable
thresholds, and tests for every rule.

## 8. Phase 6 --- Marketplace and Trade Workflow

Implement `TradingService` and REST controllers.

Suggested endpoints: - `GET /api/dashboard` - `GET /api/households` -
`POST /api/meter-readings` - `POST /api/offers` - `GET /api/offers` -
`POST /api/trades` - `GET /api/trades/{id}` -
`GET /api/privacy/public-trades` - `GET /api/privacy/households/{id}` -
`GET /api/anomalies` - `GET /api/blockchain/transactions`

A trade request should follow this order: 1. Authenticate the caller and
authorize the requested action. 2. Validate the payload and
idempotency/trade identifier. 3. Confirm that the offer is active and
quantity is available. 4. Run meter validation and anomaly rules. 5.
Reject or flag the request according to the configured policy. 6. Submit
eligible trades to the blockchain connector. 7. Store the transaction
reference and final state after appropriate confirmation. 8. Return a
clear status to the frontend.

Avoid duplicate submissions when a client retries a timed-out request.
Do not mark a trade confirmed before receiving successful blockchain
confirmation. Keep rejected attempts in the audit log.

**Deliverable:** Working offer/trade API and integration tests for
successful and rejected trade flows.

## 9. Phase 7 --- Solidity Contract and Hardhat

Create `EnergyTrading.sol`. The contract should record accepted trade
metadata and emit an event. It should enforce basic on-chain checks
independently of Java, including: - only authorized accounts can submit
trades; - trade identifiers cannot be replayed; - quantity and price
meet the contract's defined bounds; - required parties and identifiers
are valid.

Do not put names, addresses, detailed meter readings, or other private
data on-chain. Choose integer units for energy and monetary values to
avoid floating-point calculations. Document how the contract's units map
to kWh and rupees.

Recommended development workflow: 1. Compile the Solidity contract. 2.
Start the local Hardhat node. 3. Deploy the contract with a deployment
script. 4. Save the local contract address and ABI for the Java
connector. 5. Test authorization, duplicate IDs, invalid
quantities/prices, and valid trade recording. 6. Confirm the contract
emits an event for an accepted trade.

Rejected trades should not be sent to the contract as successful trades.

**Deliverable:** Compiled and deployed contract with contract-level
tests.

## 10. Phase 8 --- Web3j Integration

Implement `BlockchainService` to call the deployed contract.

Configuration should include: - local JSON-RPC endpoint; - chain ID; -
contract address; - ABI or generated Web3j wrapper; - development
account/private key provided through environment variables.

Never commit private keys. Do not use a real wallet or public-chain
funds for the college demo.

For a submitted transaction, capture: - transaction hash; - block number
when mined; - contract address; - confirmation/result status; - trade
identifier.

Handle RPC errors, reverted transactions, and timeouts. A pending
transaction is not yet a confirmed trade. Add a retry/idempotency
strategy so retries do not create duplicate on-chain records.

**Deliverable:** A successful Java-to-contract call and transaction
details available to the API.

## 11. Phase 9 --- Frontend Dashboard

Build responsive pages using HTML, CSS, JavaScript, and Bootstrap.

  -----------------------------------------------------------------------
  Page                                Main features
  ----------------------------------- -----------------------------------
  Dashboard                           Household count, active offers,
                                      confirmed trades, alerts

  Households                          Demo households and current
                                      simulated surplus/deficit

  Marketplace                         Browse offers, create offer,
                                      request trade

  Meter Security                      Submit reading, view validation and
                                      replay-check results

  Privacy Center                      Public pseudonymous view and
                                      authorized private view

  Anomaly Detection                   Alerts, rule explanations,
                                      severity, review status

  Blockchain History                  Trade ID, transaction hash, block
                                      number, status
  -----------------------------------------------------------------------

Use clear labels such as `CONFIRMED`, `PENDING`, `REJECTED`, and
`FLAGGED`. The UI must display backend results rather than pretending a
trade succeeded. Do not place secrets in JavaScript.

**Deliverable:** A usable browser interface connected to the REST API.

## 12. Phase 10 --- Integration and Demo Testing

### Scenario A: Valid trade

-   Seller has 5 kWh accepted surplus.
-   Buyer requests 3 kWh at ₹8/kWh.
-   Expected total: ₹24.
-   Expected result: validation passes, anomaly rules pass, contract
    records the trade, and the UI shows a real transaction hash and
    block number from the local chain.

### Scenario B: Manipulated reading

-   Supported surplus is 5 kWh.
-   Submitted value is 50 kWh.
-   Expected result: reject the reading/offer, show the reason, and
    write an audit event. No successful trade is recorded on-chain.

### Scenario C: Replay

-   Submit the same reading ID or sequence twice.
-   Expected result: the second submission is rejected.

### Scenario D: Overselling

-   Accepted surplus is 5 kWh.
-   Attempt to offer 100 kWh.
-   Expected result: reject before contract submission.

### Scenario E: Abnormal price

-   Offer price is ₹100/kWh with a configured threshold of ₹15/kWh.
-   Expected result: flag or reject according to the documented policy.

### Scenario F: Privacy

-   Public user requests trade history.
-   Expected result: only approved pseudonymous fields are returned.
-   Unauthorized user requests household details.
-   Expected result: access denied.
-   Authorized role requests permitted details.
-   Expected result: private fields are returned according to policy.

### Scenario G: Blockchain failure

-   Stop the local blockchain or use an invalid RPC endpoint.
-   Expected result: trade remains pending/failed as appropriate, error
    is logged, and the UI does not show it as confirmed.

## 13. Suggested Development Order

1.  Bootstrap the Spring Boot application and database.
2.  Implement entities, repositories, seed data, and basic APIs.
3.  Add meter validation and tests.
4.  Add anomaly rules and audit logs.
5.  Add privacy DTOs and server-side access control.
6.  Implement offers and trade workflow.
7.  Build and test the Solidity contract.
8.  Connect Web3j to the local chain.
9.  Build frontend pages and connect the API.
10. Run all scenarios and prepare the live demonstration.

## 14. Completion Checklist

-   [x] Backend starts successfully.
-   [x] MySQL schema and demo data are available.
-   [x] Valid meter readings can be accepted.
-   [x] Invalid and replayed readings are rejected.
-   [x] Offers cannot exceed available energy.
-   [x] Each anomaly rule has a test and readable explanation.
-   [x] Public and private data endpoints enforce the intended access
    rules.
-   [x] Smart contract enforces its own basic trade rules.
-   [x] A valid trade yields an actual local-chain transaction hash and
    block number.
-   [x] Rejected trades are audited without being presented as
    successful on-chain trades.
-   [x] Blockchain errors do not create false confirmation states.
-   [ ] All demo scenarios can be performed from the browser.
-   [x] README documents setup, configuration, and run commands.

Status (Batches 1-3): every item except the browser demo is implemented and verified by
automated tests. The local-chain transaction hash and block number were also verified live against a
Hardhat node and MySQL. "All demo scenarios can be performed from the browser" belongs to
Phases 9-10 (Batch 4).

### Implementation status by phase

| Phase | Batch | Status | Main artefacts |
|---|---|---|---|
| 1 Project setup | 1 | Done, verified | Spring Boot, MySQL, Hardhat 2 |
| 2 Database and domain model | 1 | Done, verified | `database/schema.sql` (v3), migrations V001-V003, entities, repositories |
| 3 Smart-meter validation | 2 | Done, verified | `SmartMeterValidationService`, `MeterReadingService` |
| 4 Privacy and access control | 2 | Done, verified | `PrivacyService`, `DigestService`, `PseudonymService`, `AccessPolicy` |
| 5 Rule-based anomaly detection | 2 | Done, verified | `AnomalyDetectionService`, `AnomalyRule` |
| 6 Marketplace and trade workflow | 3 | Done, verified | `MarketplaceService`, `TradingService`, `TradeStateService`, `EnergyAvailabilityService`, `trade_events` |
| 7 Solidity contract and Hardhat | 3 | Done, verified | `blockchain/contracts/EnergyTrading.sol`, `test/EnergyTrading.test.js`, `scripts/deploy.js` |
| 8 Web3j integration | 3 | Done, verified; review findings remediated | `Web3jBlockchainGateway`, `TradeSettlementService`, `TradeRecoveryService`, `SignerLeaseService`, `BlockchainAlertService`, `MarketplaceMaintenance` |
| 9 Frontend dashboard | 4 | Not started | |
| 10 Integration and demo testing | 4 | Not started | |

Implementation notes for Phases 6-8 (details in README):
- The suggested `TradingService` is split into `MarketplaceService` (offers) and `TradingService`
  (trades). The suggested `BlockchainService` is `TradeSettlementService` plus the
  `BlockchainGateway` interface, implemented with Web3j raw ABI encoding (no generated wrapper).
- Units are Wh for energy and paise for money; the contract recomputes and checks the total.
- Database and chain are reconciled by an explicit write-ahead and reconciliation workflow, not an
  atomic transaction.

Batch 3 review remediation (Phases 6-8; no change to Phases 9-10):
- **Deployment identity:** the contract (`EnergyTrading/2`) derives a `deploymentId` at
  construction; the backend requires `BLOCKCHAIN_DEPLOYMENT_ID` and checks it and the genesis hash
  on every decision, so a chain reset at the same address is detected.
- **NEEDS_REVIEW:** trades whose outcome automation cannot establish (deployment changed,
  inconsistent evidence) are held with funds reserved and resolved only by audited, evidence-gated
  administrator actions (`resolve-confirmed`, `resolve-failed`, `re-anchor`).
- **Evidence-based confirmation:** receipt target and `TradeRecorded` event, on-chain status
  Recorded or Settled, data match, unchanged deployment.
- **Resubmission:** same nonce while the old nonce is unused, so at most one attempt can be mined;
  every attempt is kept in `trade_submission_attempts`.
- **Signer lease** with fencing token across backend instances; de-duplicated `blockchain_alerts`;
  scheduled **anchor audit** of confirmed trades.
- **Privacy:** public trades without pseudonyms, public households without energy figures, a
  k=3 market summary; stored per-trade party references and key ID survive key rotation.
- Schema v3 via `V003__deployment_identity_recovery_and_signer_lease.sql`.

## 15. Final Notes

This is an academic prototype using simulated readings and trades. It
demonstrates software validation, privacy-aware data handling,
explainable anomaly rules, and blockchain-backed records. It does not
guarantee meter authenticity, complete anonymity, real electricity
delivery, or production-grade security.
