---
version: 2.0
date: 2024-09-20
author: Daniel Rangel
status: Production Ready
tags:
  - billing
  - payment
  - stripe
  - payment-intent
  - webhook
  - idempotency
related_documents:
  - ./invoice.md
  - ./subscription-lifecycle.md
  - ../01-architecture/outbox-pattern.md
---

# Payment Processing

## Summary

Billforge charges subscriptions with Stripe PaymentIntents. The backend creates
the intent, the browser confirms it with Stripe.js, and the backend marks the
invoice paid after retrieving the intent or receiving a signed webhook.

The flow has three properties:

- The secret key never reaches the browser.
- Every creation carries an idempotency key, so a double click cannot charge twice.
- The webhook is verified with Stripe's signature before any business logic runs.

## Flow

1. `POST /api/payments/process/{invoiceId}` calls `PaymentService.processPayment`.
2. `StripePaymentGateway.createPaymentIntent` creates a PaymentIntent for the
   invoice total, in cents, with metadata `receipt` and an idempotency key.
3. The response contains `paymentIntentId`, `clientSecret`, `publishableKey`,
   `amountCents` and `currency`.
4. The frontend confirms the payment with Stripe.js using the client secret.
5. The frontend calls `POST /api/payments/verify` with the PaymentIntent ID.
6. `PaymentService.verifyAndCapturePayment` retrieves the intent, checks that the
   status is `succeeded`, marks the payment SUCCEEDED and the invoice PAID.
7. Stripe also sends `payment_intent.succeeded` to the webhook. The handler is
   idempotent, so processing the same event twice does not double-credit.

A subscription upgrade uses the same flow. The invoice stays OPEN and the plan
does not change until payment verification succeeds.

## Idempotency

The key is `pay-{tenantId}-{invoiceId}`. It is used both as the database lookup
key and as the Stripe idempotency key.

- If a pending payment already exists, the service retrieves the existing
  PaymentIntent instead of creating another one.
- If Stripe receives the same key twice, it returns the original intent.
- Payment retries use `{key}-retry-{n}` so each retry is a distinct charge attempt.

Payment IDs are unique per tenant schema.

## Verification

There is no client-side signature to verify. Stripe confirms the intent and the
backend trusts only two sources:

- A direct retrieval of the PaymentIntent with the secret key.
- A webhook whose `Stripe-Signature` header validates against the webhook secret.

The backend never accepts an amount, currency or status sent by the browser.

## Webhooks

`StripeWebhookController` exposes `POST /api/webhooks/stripe`.

- The route is public because Stripe calls it server to server.
- `Webhook.constructEvent` validates the signature and timestamp.
- `payment_intent.succeeded` marks the payment and invoice paid.
- `payment_intent.payment_failed` records the failure code and schedules a retry.
- Unknown event types return 200 so Stripe does not retry them.

If internal processing fails, the controller logs the event and still returns
200. Stripe retries only transport failures, not application errors.

## Retries

`PaymentService.retryFailedPayment` creates a new PaymentIntent after a failure.
The retry count is capped at three. Backoff is 1 hour, 6 hours and 24 hours.
After the third failure the payment stays failed and the subscription is
suspended.

## Refunds

`PaymentService.refundPayment` calls `StripePaymentGateway.refundPayment`, which
creates a Stripe Refund against the PaymentIntent. A null or zero amount means a
full refund. Partial refunds are expressed in whole USD and converted to cents.

## Configuration

```yaml
stripe:
  publishable-key: ${STRIPE_PUBLISHABLE_KEY}
  secret-key: ${STRIPE_SECRET_KEY}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET}
  checkout:
    success-url: ${STRIPE_CHECKOUT_SUCCESS_URL}
    cancel-url: ${STRIPE_CHECKOUT_CANCEL_URL}
```

`STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` are required. The application
fails at startup when either is missing.

## Failure modes

| Case | Behaviour |
|---|---|
| Card declined | Payment FAILED, retry scheduled, invoice stays OPEN |
| User abandons checkout | Payment stays PENDING; the next request reuses the intent |
| Webhook duplicated | Second delivery is ignored by the status check |
| Webhook signature invalid | Request rejected with 400, no state change |
| Stripe unavailable | PaymentException with 502, invoice unchanged |
| Refund fails | RefundResult.success = false, payment stays SUCCEEDED |

## Out of scope

Billforge does not store card data, does not implement Stripe Connect, and does
not create Stripe Subscriptions. Recurring billing is modelled with invoices and
PaymentIntents created by the scheduler.
