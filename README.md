# payment-gateway

A payment gateway for FicMart, a fictional e-commerce platform. Built as part of the Backend Engineer Path.

## What this does

FicMart's order service calls this gateway to move money. The gateway sits between FicMart and a mock bank that randomly fails, adds latency, and enforces strict rules. The bank will fail. The gateway must not.

Four operations are supported:

- **Authorize** — reserve funds on a card when an order is placed
- **Capture** — charge the reserved funds when goods ship
- **Void** — release the hold if the order is cancelled before shipping
- **Refund** — return money after a delivered order is returned

## Stack

| Layer | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot |
| Persistence | Spring Data JPA / Hibernate + PostgreSQL |
| Migrations | Flyway |
| Build | Maven |

## Project structure

```
com.ficmart.gateway/
├── payment/          # authorize, capture, void, refund — controller, service, entity
├── idempotency/      # idempotency key management, reconciliation worker, expiration worker
├── bank/             # bank client interface, real HTTP client, retry decorator
└── common/           # response envelope, exceptions, global error handler
```

## Key design decisions

**Idempotency at the gateway boundary.** Every mutating request requires an `Idempotency-Key` header. Duplicate requests return the stored response — no second bank call, no second charge. Based on Brandur Leach's Stripe idempotency model.

**Two-phase atomic transactions.** Each operation commits twice: once to record intent (phase 1), then again to record the bank's outcome (phase 2). The bank call happens between phases, with no database connection held open. This means a crash mid-operation leaves a recoverable trail.

**Intermediate payment statuses as the crash-recovery signal.** `CAPTURING`, `VOIDING`, and `REFUNDING` are not just informational — they tell the reconciliation worker exactly what to replay after a crash. No separate recovery-point column needed.

**Reconciliation worker handles crash recovery.** A background job scans for payments stuck in intermediate states and re-calls the bank idempotently. The bank's own idempotency guarantees no double-charge on replay.

**Binary retry classification.** Transient failures (HTTP 500, network errors) are retried with exponential backoff + jitter. Any structured bank error code (4xx) fails fast — no retry.

**No circuit breaker.** The bank's chaos is bounded and not a sustained outage. Retry + reconciliation is sufficient. A circuit breaker's complexity is not justified here.

**Append-only audit log, not a transactional outbox.** Payment events are written atomically with each transaction phase. There is no external consumer, so the outbox pattern adds infrastructure with no benefit.

See `TRADEOFFS.md` for the full reasoning.

## Running locally

### Prerequisites

- Java 21
- Maven
- Docker (for PostgreSQL and the mock bank)

### Start the mock bank

```bash
cd bank && make up
# Bank API: http://localhost:8787
# Swagger docs: http://localhost:8787/docs
```

### Configure

```bash
cp config.example.yml src/main/resources/application-local.yml
# Edit application-local.yml with your DB credentials
```

### Start the gateway

```bash
mvn spring-boot:run
```

### Run tests

```bash
mvn test
```

## API

All `POST` endpoints require an `Idempotency-Key: <uuid>` header.

| Method | Path | Description |
|---|---|---|
| `POST` | `/payments/authorize` | Reserve funds |
| `POST` | `/payments/{id}/capture` | Charge authorized funds |
| `POST` | `/payments/{id}/void` | Cancel authorization |
| `POST` | `/payments/{id}/refund` | Return money after capture |
| `GET` | `/payments/{id}` | Payment receipt |
| `GET` | `/payments?order_id={id}` | Payments by order |
| `GET` | `/payments?customer_id={id}` | Payment history (paginated) |
| `GET` | `/payments/{id}/events` | Audit log |
| `GET` | `/health` | Health check |

### Response envelope

```json
// Success
{ "success": true, "message": "OK", "data": { ... } }

// Error
{ "success": false, "message": "...", "error": { "code": "...", "details": ... } }
```

## Payment lifecycle

```
PENDING → AUTHORIZED → CAPTURING → CAPTURED → REFUNDING → REFUNDED
                     → VOIDING  → VOIDED
                     → EXPIRED
          FAILED (from any intermediate state on permanent bank error)
```

`CAPTURING`, `VOIDING`, and `REFUNDING` are transient in-flight states. A `GET /payments/{id}` may return these — it means an operation is in progress. Poll again shortly.

## Test cards

| Card number | Balance | Use case |
|---|---|---|
| 4111111111111111 | $10,000 | Happy path |
| 4242424242424242 | $500 | Limited balance |
| 5555555555554444 | $0 | Insufficient funds |
| 5105105105105100 | $5,000 | Expired card |

CVV and expiry on file with the mock bank. See `http://localhost:8787/docs` for full test card details.

## Status

- [x] Epic 1 — Foundation (schema, entities, domain types, response envelope)
- [ ] Epic 2 — Payment operations (authorize, capture, void, refund, query)
- [ ] Epic 3 — Resilience and tests (reconciliation worker, expiration worker, full test suite)
