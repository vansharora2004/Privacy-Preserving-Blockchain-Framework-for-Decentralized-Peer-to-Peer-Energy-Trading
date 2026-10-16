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
  a rotation. Keep the key stable for the life of a demo database. If you rotate it, note the
  rotation date for anomaly review.

## 4. Public and private views

| View | Endpoint | Contents |
|---|---|---|
| Public | `GET /api/privacy/public/households` (anonymous) | pseudonym and validated sellable surplus only |
| Private | `GET /api/privacy/households/{code}`, `GET /api/me` | code, pseudonym, alias, name, e-mail, role, balance, surplus/deficit (never credentials) |
| Private readings | `GET /api/households/{code}/meter-readings` | detailed readings with validation status and digest |

JPA entities are never returned from controllers; every response is a dedicated DTO.

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
- **Known limitation:** when a database unique constraint is violated (for example, during a
  concurrent duplicate submission), Hibernate's `SqlExceptionHelper` logs the conflicting key
  value at ERROR level, such as a reading ID. These values are identifiers, not credentials. The
  log line is kept because silencing all SQL error logging would hide real faults.

## 7. Known operational issue: Batch 1 demo passwords

Databases seeded by the very first project checkpoint (`f46267a`) used hardcoded demo
passwords that are visible in Git history. The existing `energy_trading` database was seeded
that way. Now that authentication is enforced, reset those three accounts' passwords, or
recreate the demo database with `DEMO_HOUSEHOLD_PASSWORD` and `DEMO_ADMIN_PASSWORD` set,
before exposing the API beyond localhost.
