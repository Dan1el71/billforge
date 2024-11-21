---
version: 2.0
date: 2024-09-20
author: Daniel Rangel
status: Production Ready
tags:
  - security
  - webhooks
  - stripe
related_documents:
  - ../05-platform-billing/payment-processing.md
  - ../12-decisions/ADR-003-stripe-payment-intents.md
---

# Webhook Security

## Endpoint

`POST /api/v1/webhooks/stripe`

The endpoint is public. Stripe calls it from its own infrastructure, so it
cannot require a JWT. Authentication comes from the `Stripe-Signature` header.

## Verification

```java
Event event = Webhook.constructEvent(payload, signature, webhookSecret);
```

`Webhook.constructEvent` does three things:

1. Recomputes the HMAC-SHA256 of the raw request body with the webhook secret.
2. Compares it with the signature using a constant-time comparison.
3. Rejects the payload when the timestamp is outside the tolerance window.

A failure returns 400 and no business logic runs.

## Why the raw body matters

The signature covers the exact bytes Stripe sent. Reading the body through a
framework binding that reformats JSON, or validating a parsed object instead of
the raw string, invalidates the signature. The controller takes `@RequestBody
String payload` deliberately.

## Replay protection

Stripe includes a timestamp in the signature header. The SDK rejects events
older than the default tolerance, which prevents an attacker from replaying a
captured request indefinitely.

Application-level idempotency is a second layer: `handlePaymentSuccess` returns
early when the payment is already SUCCEEDED.

## Events

| Event | Handling |
|---|---|
| `payment_intent.succeeded` | Mark payment SUCCEEDED, invoice PAID |
| `payment_intent.payment_failed` | Record failure code, schedule retry |
| Anything else | Acknowledge with 200 and ignore |

## Failure policy

- Invalid signature: 400, no state change.
- Unknown event: 200, ignored.
- Internal processing error: logged, still 200.

Returning 200 on internal errors avoids an infinite retry loop for events we
have already accepted. Errors are surfaced through logs and metrics, not through
Stripe retries.

## Secrets

`STRIPE_WEBHOOK_SECRET` is separate from the API secret key. It is provisioned
per endpoint and rotated independently. The application fails at startup when it
is missing.

## Local testing

```bash
stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe
```

The CLI prints the webhook secret for the session. Use that value as
`STRIPE_WEBHOOK_SECRET` while testing.

## Checklist

- [x] Signature verified before parsing business fields
- [x] Raw body preserved
- [x] Replay window enforced by the SDK
- [x] Handler is idempotent
- [x] Secret loaded from environment, never committed
- [x] Webhook path excluded from JWT authentication but not from signature checks
