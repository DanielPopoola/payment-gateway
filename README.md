# payment-gateway

A payment gateway sitting between FicMart's order service and a mock bank API.
Handles authorization, capture, void, and refund — with idempotency, crash
recovery, and strict state machine enforcement.

## Stack

| Layer | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot |
| Persistence | Spring Data JPA + PostgreSQL |
| Migrations | Flyway |
| Build | Maven |

## How it works

FicMart sends payment requests to this gateway. The gateway reserves funds with
the bank, tracks payment state, and handles everything that can go wrong between
the two: duplicate requests, mid-operation crashes, bank failures, and
authorization expiry.

Four operations:

- **Authorize** — reserve funds when an order is placed
- **Capture** — charge the reserved funds when goods ship
- **Void** — release the hold if the order is cancelled
- **Refund** — return money after a delivered order is returned

## Running locally

**Prerequisites:** Java 21, Maven, Docker
```bash
# Get bank repo from and follow instructions in README
https://github.com/benx421/payment-gateway


```bash
# Start the mock bank
cd bank && make up

# Start PostgreSQL
docker compose up -d

# Run the gateway
mvn spring-boot:run
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

Swagger UI available at `http://localhost:8080/swagger-ui.html` when running locally.

### Response envelope

```json
{ "success": true, "message": "OK", "data": { ... } }
{ "success": false, "message": "...", "error": { "code": "...", "details": ... } }
```

## Key design decisions

**Two-phase transactions.** Each operation commits twice — once before the bank
call, once after. No database connection held open across a network call. A crash
between phases leaves a recoverable trail.

**Intermediate statuses as crash signals.** `CAPTURING`, `VOIDING`, and
`REFUNDING` tell the reconciliation worker exactly what to replay after a crash.
No separate recovery-point column needed.

**Three concurrency mechanisms for three race windows.** Unique constraint guards
insertion races. `locked_at` guards in-flight duplicate requests. `SELECT FOR
UPDATE` + intermediate status guards competing operations with different keys.

**Binary retry classification.** Any structured bank error code (4xx) fails fast.
HTTP 500 and network errors retry with exponential backoff and jitter.

**No circuit breaker.** Bank chaos is bounded. Retry + reconciliation worker is
sufficient. A circuit breaker's complexity is not justified here.

See `TRADEOFFS.md` for full reasoning.

## Payment lifecycle

```
PENDING → AUTHORIZED → CAPTURING → CAPTURED → REFUNDING → REFUNDED
                     → VOIDING  → VOIDED
                     → EXPIRED
          FAILED (permanent bank rejection from any operation)
```

## Test cards

| Card | Balance | Use case |
|---|---|---|
| 4111111111111111 | $10,000 | Happy path |
| 4242424242424242 | $500 | Limited balance |
| 5555555555554444 | $0 | Insufficient funds |
| 5105105105105100 | $5,000 | Expired card |