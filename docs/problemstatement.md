# Problem Statement

## Project Title

**Privacy-Preserving and Trust-Aware Blockchain Framework for
Decentralized Peer-to-Peer Energy Trading**

## 1. Background

Distributed energy resources such as rooftop solar panels can allow a
household to produce more electricity than it consumes at certain times.
A peer-to-peer (P2P) energy-trading model lets a household with
simulated surplus offer energy to another household with a simulated
deficit. A digital platform is needed to coordinate offers, validate
readings, apply trading rules, protect household information, and
maintain trustworthy transaction records.

A conventional centralized application can store trades in a database,
but users may have limited independent visibility into whether records
have been changed. In addition, meter readings can be incorrect or
manipulated, unusual trading activity may go unnoticed, and exposing
detailed household energy data can reveal behavioral patterns.

## 2. Problem Statement

The project aims to design and implement a prototype P2P energy-trading
framework that supports trustworthy simulated trades while addressing
three connected concerns:

1.  **Meter-data trust:** A user may submit an implausible, duplicated,
    replayed, or manipulated energy reading. If the marketplace accepts
    it without checks, the user may offer more energy than is available.
2.  **Household privacy:** Publishing detailed household identities,
    meter readings, and energy-use patterns can disclose sensitive
    information. A design must limit public data exposure while allowing
    authorized access where needed.
3.  **Abnormal or fraudulent trading:** Overselling, duplicate
    transactions, unusual prices, and excessive trading frequency can
    undermine confidence in the marketplace. The application needs
    clear, explainable detection rules and an audit trail.
4.  **Transaction integrity:** Participants need a verifiable record of
    accepted trades. A blockchain smart contract can record trade
    commitments and transaction metadata, while the application database
    manages private and operational details.

The proposed solution combines server-side meter validation,
privacy-aware data separation, rule-based anomaly detection, and a
Solidity smart contract on a local blockchain. It is intended as an
educational prototype with simulated energy values, not a production
electricity-market system.

## 3. Objectives

-   Build a browser-based P2P energy marketplace for simulated household
    offers and purchases.
-   Validate energy readings for non-negative values, plausibility,
    consistency, and duplicate/replay attempts.
-   Prevent a seller from offering more energy than the accepted
    available surplus.
-   Apply explainable rules to detect suspicious quantities, prices,
    duplicate activity, and abnormal frequency.
-   Keep personal household details and detailed readings off-chain in
    MySQL.
-   Provide pseudonymous public transaction views and role-protected
    private views.
-   Record accepted trades on a local EVM blockchain using a Solidity
    smart contract.
-   Display the real transaction hash, block number, and confirmation
    status for successful demo trades.
-   Maintain application audit logs for rejected attempts and flagged
    events.
-   Demonstrate the complete workflow through a simple web dashboard.

## 4. Proposed Solution

The user interacts with an HTML/CSS/JavaScript frontend. Requests are
sent to a Java Spring Boot backend, where authentication, authorization,
input validation, and business rules are applied.

The backend validates meter readings, runs configurable anomaly rules,
and coordinates eligible trades. MySQL stores household records,
readings, offers, transaction metadata, and audit logs. Sensitive
information remains off-chain. Accepted trades are sent through Web3j to
a Solidity smart contract deployed on a local Hardhat blockchain. The
contract enforces core on-chain checks and emits a trade event. The
application stores the returned transaction hash and block number after
confirmation.

Rejected trades are recorded in the application audit log and must not
be presented as successful blockchain transactions. The system simulates
energy transfer and does not interact with physical meters or the
electrical grid.

## 5. Functional Requirements

1.  Users can view a dashboard of households, offers, trades, and recent
    alerts.
2.  A household can submit a simulated meter reading and create an
    offer.
3.  The backend checks reading validity and prevents duplicate or
    replayed reading identifiers.
4.  Buyers can request an amount from an active offer.
5.  The system checks available energy, quantity, price, and configured
    anomaly rules before submission.
6.  Accepted trades are recorded by the smart contract and linked to
    their on-chain transaction details.
7.  Users can view a limited pseudonymous public trade history.
8.  Authorized roles can view private household details.
9.  The system records validation failures, suspicious activity, and
    rejected trades.
10. The interface displays clear statuses and reasons for rejection or
    review.

## 6. Non-Functional Requirements

-   **Security:** Server-side authentication and authorization, safe
    password storage, input validation, and secret management.
-   **Privacy:** Data minimization, pseudonymous public identifiers, and
    off-chain storage of personal details.
-   **Integrity:** Database constraints, audit logs, and hashes for
    selected record-integrity checks.
-   **Explainability:** Each anomaly result identifies the rule that
    triggered it.
-   **Usability:** A straightforward dashboard suitable for a live
    academic demonstration.
-   **Reliability:** Blockchain errors and timeouts must not cause the
    interface to report an unconfirmed trade as successful.
-   **Maintainability:** Separate controllers, services, repositories,
    and smart-contract code.

## 7. Scope

### Included

-   Simulated households and meter readings
-   Offer creation and simulated P2P trades
-   Meter consistency and replay checks
-   Rule-based anomaly detection
-   Public and authorized privacy views
-   MySQL persistence and audit logging
-   Solidity contract deployment to a local Hardhat network
-   Web3j integration and transaction-history display

### Excluded

-   Connection to real smart meters or grid infrastructure
-   Actual electricity delivery, grid balancing, or utility billing
-   Production payment processing
-   A claim of guaranteed anonymity
-   Machine-learning-based detection as a required component
-   Deployment to a public blockchain

## 8. Expected Outcomes

The completed prototype should demonstrate that: - a valid simulated
trade can be accepted and recorded on-chain; - manipulated, duplicate,
or unsupported readings are rejected; - trades that violate configured
rules are rejected or flagged for review; - public pages expose less
household information than authorized views; - successful on-chain
trades display verifiable transaction references; and - rejected
attempts remain auditable without being misrepresented as successful
blockchain transactions.

## 9. Success Criteria

The project will be considered demonstrable when the valid-trade
scenario succeeds end to end, the defined invalid scenarios are blocked
or flagged as specified, access-control tests distinguish public from
authorized data, and the UI can show a confirmed transaction hash and
block number from the local blockchain.
