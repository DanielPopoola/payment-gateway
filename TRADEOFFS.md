# TRADEOFFS.md

## Architecture choices (why this structure?)

I structured it such that each package contains all it's feature concerns. You don't need to touch 5 files to know about payment operations/, they are all contained in the `payment/` folder, likewise idempotency in it's own. All transaction logic is one place `PaymentTransaction` with the two phases for each operation: an atomic call before calling bank and another after calling bank, making sure database transactions aren't held over network calls.


## State management

I track payment state with the use of the enums in the `PaymentStatus` module. Invalid transitions are raised at runtime with the error `InvalidTransitionException` via the `transitionTo` method.


## Failure Handling

Since the bank as two major types of failures `4xx` and `5xx` errors, I simply classify the errors into those two categories, failures with error code `4xx` fail fast; only `5xx` errors are retried with exponential backoff + jitter.

For partial failures, I use intermediate states to track intent first, e.g a `CAPTURE` operation, first atomically saves state as `CAPTURING` to the gateway before calling bank, such that in case of any failure, the reconciliation worker sees a stuck payment and retries accordingly.

## Idempotency

Well I implemented idempotency in a straightforward way, the idempotency key has it's own table with the idempotencykey, operation, requestHash, response code, and response payload. The requestHash was to prevent cases where different requests were sent to the gateway but with the same idempotency key; the   `operation` was so that I could find a specific idempotency key by it's paymentId because one paymentId could have many idempotency keys; the response payload is simply the `Payment` object which is serialized to json for duplicate requests; then for preventing contrasting requests, I use lock on the database in a transaction, so that one event of a race condition, only one operation suceeds.


## What I'd Do Differently

Wire up monitoring with Prometheus and Grafana so that monitoring for insights into latency and failure rate of bank service