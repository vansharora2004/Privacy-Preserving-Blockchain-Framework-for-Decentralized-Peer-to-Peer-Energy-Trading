# Privacy, Integrity and Access Control (Batch 2)

This document describes what the Batch 2 privacy and security features do, and what they
do **not** do. The project is an academic simulation. It does not provide complete
anonymity or real-world smart-grid privacy guarantees.

## 1. Data placement

- Personal and sensitive data stays off-chain in MySQL: names, e-mail addresses, password
  hashes, roles, detailed meter readings, anomaly logs and audit logs.
- Nothing personal is written to the blockchain in Batch 2, which makes no blockchain calls at all.
  Batch 3 must not publish names, e-mail addresses, household codes, password hashes or raw
  readings on-chain.

## 2. Canonical SHA-256 digests (integrity, not confidentiality)

`DigestService` hashes a **canonical record** (`CanonicalRecord`), not arbitrary JSON:

| Aspect | Rule |
|---|---|
| Field order | Fixed in code for each record type and version (`meter-reading/v1`) |
| Strings | Unicode NFC, UTF-8, length-prefixed (`<bytes>:<value>`), so separators cannot shift field boundaries |
| Numbers | `BigDecimal` plain notation, trailing zeros removed (`5` = `5.0` = `5.000`); NaN and Infinity rejected |
| Timestamps | Truncated to microseconds (MySQL `DATETIME(6)`), format `yyyy-MM-dd'T'HH:mm:ss.SSSSSS` |
| Null | `~` (a non-null string is always length-prefixed, so it can never collide) |

Meter-reading digest input (the validation status is excluded because it can change during review):

```
meter-reading/v1|householdCode=<len>:<code>|readingId=<len>:<id>|sequenceNo=<n>
|energyGeneratedKwh=<n>|energyConsumedKwh=<n>|availableKwh=<n>|readingTime=<timestamp>
```

`GET /api/meter-readings/{readingId}/integrity` recomputes the digest and returns only
`MATCH`, `MISMATCH` or `NO_STORED_DIGEST`, never the record contents. The two seed readings
created in Batch 1 used an older pipe-separated format; they are still verified and reported as
`LEGACY_PIPE_SHA256_V0`, so existing rows did not need to be changed.

**Limitations**

- A digest detects changes made **after** it was stored. It does not prove that the reading was
  truthful when recorded. Software checks cannot establish the truth of a physical measurement.
- A purely local digest does not protect against an administrator who can modify both the
  record and its stored digest. External anchoring (for example on-chain, in Batch 3) narrows
  but does not remove that gap.
- A plain SHA-256 of predictable data is **not anonymous**. Meter values and identifiers have
  small value ranges and can be brute-forced to confirm a guess, so digests must not be treated
  or published as a privacy mechanism.

## 3. Pseudonymous identifiers

Public and log views refer to households as `hh-` followed by the first 20 hex characters of
`HMAC-SHA-256(PRIVACY_PSEUDONYM_KEY, "household|" + householdCode)`.

- The key comes **only** from the `PRIVACY_PSEUDONYM_KEY` environment variable (at least 32
  characters). It has no default, is never logged, and is redacted from `toString()`. The
  application **refuses to start** if the key is missing or too short, and the error never echoes
  the configured value.
- A keyed HMAC is used because a public hash of a household code could be recomputed by anyone
  who guesses the code.
- Pseudonyms are **stable**, so all records with the same pseudonym can be linked to each other
  and may reveal patterns. This is pseudonymity, not anonymity.
- **Key rotation:** changing the key changes every pseudonym. Anomaly logs store the pseudonym
  that was current when they were written, so older entries no longer match current views after
  a rotation. Keep the key stable for the life of a demo database.
- **Trades survive a rotation (schema v3).** At reservation the backend stores each trade's
  on-chain party references (`seller_ref`, `buyer_ref`) and `privacy_key_id`, a one-way identifier
  of the key (`"k-"` + 16 hex characters of an HMAC of a fixed label). The raw key is never stored.
  Verification and resubmission use the stored references, so trades keep matching their on-chain
  record after a rotation. Trades created before V003 have no stored references; they are derived
  from the current key and become unverifiable (party fields) once the key changes.
- **Rotation procedure:** (1) let PENDING and SUBMITTED trades settle, or note them; (2) run the
  anchor audit and resolve its alerts; (3) change `PRIVACY_PSEUDONYM_KEY` and restart; (4) record
  the date and the old and new `privacy_key_id` values (from the database, never the key) for
  anomaly review. Old pseudonyms in anomaly logs cannot be mapped to new ones afterwards.

## 4. Public and private views

| View | Endpoint | Contents |
|---|---|---|
| Public | `GET /api/privacy/public/households` (anonymous) | household pseudonyms only, with no energy figures |
| Public | `GET /api/privacy/public/market-summary` (anonymous) | number of open offers and of trades confirmed in the last 24 h; total offered kWh and min/max price only when at least **3** sellers have open offers (otherwise those fields are null and `figuresWithheld=true`) |
| Public | `GET /api/privacy/public/trades` (anonymous) | confirmed trades as quantity, price, transaction hash, block number and confirmation time; **no** pseudonyms, trade IDs or offer IDs |
| Private | `GET /api/privacy/households/{code}`, `GET /api/me` | code, pseudonym, alias, name, e-mail, role, balance, surplus/deficit (never credentials) |
| Private readings | `GET /api/households/{code}/meter-readings` | detailed readings with validation status and digest |

JPA entities are never returned from controllers; every response is a dedicated DTO.

**Why the public views changed (Batch 3 review).** Previously the public
household list showed each pseudonym with its sellable surplus, and the public trade list showed
seller and buyer pseudonyms. Together these let anyone follow one household's energy position and
trading partners over time. The directory now lists pseudonyms only, the public trades carry no
household reference, and market figures are aggregated with a minimum of three sellers so a single
household's offer cannot be read off the total. The minimum is a simple k-threshold, not
differential privacy: repeated observation of a small market can still reveal changes.

## 5. Authentication and authorization

- **Authentication:** HTTP Basic over a stateless API. The username is the household code and
  the password is checked against the stored BCrypt hash. Basic credentials are only
  base64-encoded, so use HTTPS outside localhost.
- **CSRF:** CSRF tokens are disabled because there are no sessions or cookies. State-changing
  endpoints accept only `application/json`, which a cross-site HTML form cannot send without a
  CORS preflight.
- **CORS:** only origins listed in `CORS_ALLOWED_ORIGINS` are allowed; there is never a wildcard
  with credentials.

| Role | Allowed |
|---|---|
| Anonymous | `GET /api/health`, `GET /api/privacy/public/**` |
| `ROLE_HOUSEHOLD` | Submit its **own** readings; read its own profile and readings; integrity checks on its own readings; offer pre-check for itself |
| `ROLE_ADMIN` | Read every household's private profile and readings (never password hashes); list accounts; read and review anomalies; read audit logs; integrity checks. **Cannot** submit meter readings or offers, because meter data must come from the household itself |
| `ROLE_AUDITOR` | Read anomaly and audit logs; integrity checks; its own profile. **No** access to household private profiles or detailed readings |

Ownership is checked on the server (`AccessPolicy`). Changing a household code or reading ID in
a URL never grants access. Denials return HTTP 403 and are recorded in the audit log.
Unauthenticated requests get HTTP 401 with a `WWW-Authenticate` challenge. The audit-log API
does not expose the stored client IP address.

## 6. Logging

- Application logs use pseudonyms, never household codes, names or e-mail addresses. Passwords,
  password hashes and the pseudonym key are never logged.
- **Constraint violations (review finding L4):** Hibernate's `SqlExceptionHelper` used to log the
  conflicting key value (a reading ID, idempotency key or transaction hash) at ERROR level. Its
  logger is now off (`logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF`). The
  exception still propagates: a constraint violation returns 409 "The request conflicts with
  existing data" and is logged as a WARN line with the method, path and **constraint name only**;
  any other SQL fault still surfaces as an exception logged by its caller. Trade-off: the raw
  driver message is no longer in the log, so diagnosing an unusual SQL error may need the logger
  re-enabled temporarily on a test database.

## 7. On-chain data (Batch 3)

The `EnergyTrading` contract stores only what is needed to identify and verify a trade:

| On-chain field | Value | Why it is not personal data |
|---|---|---|
| `tradeKey` | SHA-256(`"energy-trade|" + tradeId`) | The trade ID is a random UUID kept off-chain; the key cannot be reversed |
| `sellerRef`, `buyerRef` | HMAC-SHA-256(pseudonym key, `"trade-party|" + tradeId + "|" + role + "|" + householdCode`), stored with the trade | Keyed and different in every trade, so the references alone do not link a household's trades without the key |
| `quantityWh`, `pricePaisePerKwh`, `totalPaise` | Integer amounts | Trade terms only, with no meter readings |
| `recordDigest` | SHA-256 of the canonical `trade/v1` record | Lets the backend prove its record is unchanged; includes the random trade ID, so it cannot be confirmed by guessing amounts |

Names, e-mail addresses, household codes, passwords, password hashes, meter readings and access
decisions never go on-chain. Limitations:
- **Unlinkability is limited.** Amounts, timing, block order and the single recorder address are
  public, so trades can still be correlated (for example, several trades from one offer have the
  same price and appear close together). The per-trade references prevent direct linking by
  identifier, not inference from these side channels.
- The backend, which holds the key, can link the references.
- After a key rotation, trades with stored references stay verifiable; trades from before V003
  lose party-field verification (section 3). The record digest stays verifiable in both cases.
- The public trade view shows a transaction hash for each trade, which anyone can look up on the
  chain to see the on-chain record (the fields above, never identities).
- After a chain reset, re-anchored trades are recorded again on the new deployment with the same
  references and digest; the old deployment's record, if it still exists, is not removed.

The signer private key (`BLOCKCHAIN_SIGNER_PRIVATE_KEY`) is read only from the environment. It is
held inside Web3j `Credentials`, redacted from configuration `toString()`, and never logged. Only
the signer's public address is shown, by the admin status endpoint.

## 8. Known operational issue: Batch 1 demo passwords

Databases seeded by the very first project checkpoint (`f46267a`) used hardcoded demo
passwords that are visible in Git history. The existing `energy_trading` database was seeded
that way. Now that authentication is enforced, reset those three accounts' passwords, or
recreate the demo database with `DEMO_HOUSEHOLD_PASSWORD` and `DEMO_ADMIN_PASSWORD` set,
before exposing the API beyond localhost.
