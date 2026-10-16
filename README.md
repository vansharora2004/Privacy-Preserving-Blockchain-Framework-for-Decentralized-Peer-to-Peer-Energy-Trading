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
│       │   │   └── service/  # Core business services
│       │   └── resources/
│       │       └── application.properties
│       └── test/             # JUnit & MockMvc integration tests
├── blockchain/               # Hardhat EVM local development environment
│   ├── contracts/            # Solidity smart contracts (Batch 3)
│   ├── scripts/              # Deployment and test scripts
│   ├── test/                 # Hardhat contract tests
│   ├── hardhat.config.js     # Hardhat network & compiler configuration
│   └── package.json
├── database/
│   └── schema.sql            # Versioned MySQL DDL and schema definition
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
- **Java**: JDK 21+ (Java 22 verified)
- **Maven**: 3.9+
- **Node.js**: v20+ / v24+
- **MySQL**: 8.0+

### 2. Environment Configuration
Copy `.env.example` to `.env` or export environment variables:
```bash
# Windows PowerShell
$env:DB_HOST="localhost"
$env:DB_PORT="3306"
$env:DB_NAME="energy_trading"
$env:DB_USERNAME="root"
$env:DB_PASSWORD="your_password"
```

### 3. Running Backend (Spring Boot)
```powershell
cd backend
mvn spring-boot:run
```
- Health Check: `http://localhost:8082/api/health`

### 4. Running Local Blockchain (Hardhat)
```powershell
cd blockchain
npx hardhat node
```
- JSON-RPC Endpoint: `http://127.0.0.1:8545`
- Chain ID: `31337`

---

## Implementation Batches
- **Batch 1 (Completed)**: Project Scaffolding, Domain Model, Database Entities, Repositories, Health Endpoint & Hardhat Setup.
- **Batch 2 (Next)**: Smart-Meter Validation Service, Privacy Engine (SHA-256 Hashing & Role-based Access), and Rule-based Anomaly Detection.
- **Batch 3**: Solidity Smart Contract (`EnergyTrading.sol`), Deployment Scripts, Web3j Blockchain Connector, and Trading API.
- **Batch 4**: Frontend Dashboard (Bootstrap/HTML/JS) and End-to-End Scenario Verification (Scenarios A through G).
