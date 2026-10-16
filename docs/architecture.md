# System Architecture

## Project

**Privacy-Preserving and Trust-Aware Blockchain Framework for
Decentralized Peer-to-Peer Energy Trading**

## 1. Purpose

This project demonstrates a simulated peer-to-peer (P2P) energy
marketplace in which households can offer surplus electricity and
purchase energy from other households. The application combines
smart-meter validation, privacy-aware data handling, rule-based anomaly
detection, and blockchain-backed trade records.

> **Scope:** Energy readings and delivery are simulated for a college
> demonstration. The system does not connect to physical meters or
> control the electrical grid.

## 2. High-Level Architecture

``` mermaid
flowchart TD
    U[Household / Administrator] --> UI[Web UI: HTML, CSS, JavaScript, Bootstrap]
    UI --> API[Spring Boot REST API]
    API --> AUTH[Authentication and Role-Based Authorization]
    AUTH --> VALID[Smart-Meter Validation Service]
    VALID --> PRIV[Privacy Service]
    PRIV --> ANOM[Rule-Based Anomaly Detection]
    ANOM --> TRADE[Trading and Marketplace Service]
    TRADE --> DB[(MySQL Database)]
    TRADE --> WEB3[Web3j Blockchain Connector]
    WEB3 --> SC[Solidity Energy Trading Smart Contract]
    SC --> CHAIN[Local Hardhat EVM Blockchain]
    API --> AUDIT[Application Audit / Anomaly Logs]
    AUDIT --> DB
    UI --> PUB[Public Pseudonymous Transaction View]
    API --> PRIVVIEW[Authorized Private Data View]
    PRIVVIEW --> DB
```

## 3. Components

### 3.1 Presentation layer

A browser-based interface provides: - Dashboard and household
summaries - Energy offers and trade creation - Meter validation
results - Privacy center with public and authorized views - Anomaly
alerts and review status - Blockchain transaction history

The frontend calls Spring Boot REST endpoints. It must not contain
database credentials, private keys, or authorization secrets.

### 3.2 Backend API

Spring Boot exposes REST endpoints, validates request payloads, applies
authentication and authorization, coordinates services, and returns
structured results.

Suggested service classes: - `SmartMeterValidationService` -
`PrivacyService` - `AnomalyDetectionService` - `TradingService` -
`BlockchainService` - `AuditService`

### 3.3 Smart-meter validation

Before a reading can support an offer, validate: - Required fields and
valid household identity - Non-negative energy values - Plausible
reading range and consistency with prior readings - Unique reading
identifier and sequence number to reduce replay risk - Whether the
offered quantity is supported by the latest accepted reading

A failed validation blocks trade execution and records a rejection
reason in the application audit log. Validation improves confidence but
cannot prove that a physical meter is truthful without trusted hardware
or an independent source.

### 3.4 Privacy service

-   Keep household identity, detailed meter readings, and access-control
    data in MySQL.
-   Expose pseudonymous household identifiers and only the fields needed
    for a public trade view.
-   Require authentication and appropriate roles for private household
    details.
-   Use hashes to detect changes to selected records.
-   Avoid putting personal information or detailed readings directly
    on-chain.

Pseudonyms and hashes do not by themselves guarantee anonymity: public
data may still be linkable or reveal patterns.

### 3.5 Rule-based anomaly detection

Rules can identify or flag: - Selling more energy than the accepted
available surplus - Negative or otherwise invalid quantities - Reused
transaction or meter-reading identifiers - Prices outside configurable
limits - Excessive trades in a configured time window

The result should distinguish **rejected** (a hard validation or policy
failure) from **flagged for review** (unusual but potentially
legitimate). Rule thresholds must be configurable and documented.
Machine learning is not required.

### 3.6 Trading service

The trading service coordinates the workflow: 1. Authenticate the
requester and check permissions. 2. Validate the meter reading and
energy availability. 3. Apply anomaly rules and price/quantity policy.
4. Check the offer and buyer's requested quantity. 5. Submit an allowed
trade to the smart contract. 6. Store the resulting transaction
reference and status in MySQL. 7. Return the outcome to the frontend.

Use transaction states such as `PENDING`, `CONFIRMED`, `REJECTED`, and
`FLAGGED`. Do not display a trade as confirmed until the blockchain
submission has been confirmed. Rejected attempts belong in the
application audit log, not as successful on-chain trades.

### 3.7 Smart contract and local blockchain

A Solidity contract on a local Hardhat EVM network provides an immutable
trade record for accepted demo trades. The contract should enforce its
own basic rules, such as authorized callers, positive quantity, valid
price, and unique trade identifiers. Backend checks alone are not
sufficient.

Web3j connects Java to the deployed contract. Store the contract
address, chain ID, transaction hash, block number, and confirmation
status. Keep development private keys in local environment variables or
a local secrets file excluded from version control.

The chain proves that a transaction was recorded; it does not prove that
physical electricity was delivered.

### 3.8 MySQL data store

MySQL stores operational and private data, including user accounts,
accepted meter readings, offers, trade metadata, anomaly events, and
audit logs. Enforce database constraints for unique identifiers and use
migrations or a versioned schema as the project evolves.

## 4. Suggested Data Model

  -----------------------------------------------------------------------
  Entity                  Purpose                 Example fields
  ----------------------- ----------------------- -----------------------
  `User`                  Household identity and  `id`, `householdCode`,
                          access role             `displayAlias`, `role`,
                                                  `passwordHash`

  `MeterReading`          Submitted energy        `id`, `householdId`,
                          measurement             `readingId`,
                                                  `sequenceNo`,
                                                  `availableKwh`,
                                                  `readingTime`,
                                                  `validationStatus`

  `EnergyOffer`           Seller's available      `id`, `sellerId`,
                          offer                   `readingId`,
                                                  `quantityKwh`,
                                                  `pricePerKwh`, `status`

  `TradeTransaction`      Application             `id`, `tradeId`,
                          representation of a     `buyerId`, `sellerId`,
                          trade                   `quantityKwh`,
                                                  `pricePerKwh`,
                                                  `totalAmount`,
                                                  `status`, `txHash`,
                                                  `blockNumber`

  `AnomalyLog`            Validation and anomaly  `id`, `tradeId`,
                          results                 `ruleCode`, `severity`,
                                                  `description`,
                                                  `createdAt`,
                                                  `reviewStatus`

  `AuditLog`              Security-relevant       `id`, `actorId`,
                          actions and outcomes    `action`, `result`,
                                                  `timestamp`
  -----------------------------------------------------------------------

Use decimal-safe types for currency and carefully chosen numeric types
for energy. Avoid floating-point arithmetic for monetary settlement.

## 5. Example REST API

  --------------------------------------------------------------------------------
  Method                  Endpoint                         Purpose
  ----------------------- -------------------------------- -----------------------
  `GET`                   `/api/dashboard`                 Summary counts and
                                                           recent activity

  `GET`                   `/api/households`                Authorized household
                                                           listing

  `POST`                  `/api/meter-readings`            Submit a simulated
                                                           meter reading

  `POST`                  `/api/offers`                    Create an energy offer

  `GET`                   `/api/offers`                    List active offers

  `POST`                  `/api/trades`                    Validate and request a
                                                           trade

  `GET`                   `/api/trades/{id}`               Read a trade status

  `GET`                   `/api/privacy/public-trades`     Return a limited
                                                           pseudonymous view

  `GET`                   `/api/privacy/households/{id}`   Return private details
                                                           only to authorized
                                                           roles

  `GET`                   `/api/anomalies`                 List detected anomalies

  `GET`                   `/api/blockchain/transactions`   List recorded
                                                           transaction references
  --------------------------------------------------------------------------------

These endpoints are a proposed interface; implement and test
authorization for each endpoint.

## 6. Security and Reliability

-   Hash passwords with a suitable password-hashing algorithm; never
    store plain-text passwords.
-   Use role-based authorization on the server, not only by hiding
    frontend controls.
-   Validate all request fields and apply request-size and rate limits
    where appropriate.
-   Do not log passwords, private keys, or unnecessary personal
    information.
-   Keep blockchain keys and database credentials out of source control.
-   Handle failed blockchain calls and timeouts without falsely marking
    trades confirmed.
-   Make trade submission idempotent so retries do not create duplicate
    trades.
-   Record audit events for rejected readings and flagged/rejected
    trades.
-   Use a local development chain only for the demo; a production
    deployment would need additional threat modeling, key management,
    privacy analysis, and operational controls.

## 7. End-to-End Trade Flow

1.  A seller submits a simulated reading and available surplus.
2.  The backend validates the reading and its identifier/sequence.
3.  The privacy service limits what is exposed to other users.
4.  The anomaly service evaluates quantity, price, duplicate, and
    frequency rules.
5.  The trading service either rejects/flags the attempt or prepares an
    allowed trade.
6.  The backend submits the trade to the Solidity contract through
    Web3j.
7.  The contract enforces on-chain rules and emits a trade event.
8.  After confirmation, the backend stores the transaction hash and
    block number.
9.  The frontend displays the final status and the appropriate public or
    authorized details.

## 8. Demonstration Scenarios

-   **Valid trade:** Seller has 5 kWh available; buyer purchases 3 kWh
    at ₹8/kWh; total is ₹24. Display the confirmed transaction hash and
    block number.
-   **Manipulated reading:** A household has a supported surplus of 5
    kWh but submits 50 kWh. The consistency rule rejects the
    reading/trade.
-   **Replay attempt:** Submit the same reading ID or sequence twice;
    the second submission is rejected.
-   **Overselling:** Attempt to sell 100 kWh when only 5 kWh is
    available; reject it before contract submission.
-   **Suspicious price:** A trade priced at ₹100/kWh exceeds an example
    configurable threshold of ₹15/kWh and is flagged or rejected
    according to policy.
-   **Privacy view:** Show pseudonymous identifiers in the public view
    and household details only to an authorized role.
-   **Integrity check:** Recompute a stored record hash and show whether
    the record matches the recorded digest.

## 9. Technology Stack

-   Java 21 and Spring Boot
-   Maven, Spring Data JPA, Spring Security
-   MySQL
-   HTML, CSS, JavaScript, Bootstrap
-   Solidity and Hardhat local network
-   Web3j for Java-to-EVM interaction
-   Java cryptographic APIs for hashing

## 10. Scope Limitations

The prototype simulates readings, trades, and settlement. It does not
validate actual grid physics, guarantee anonymity, establish real-world
meter authenticity, or prove electricity delivery. Blockchain
immutability supports auditability, but correctness still depends on the
data and rules supplied to the system.
