---
version: 2.0
date: 2024-09-20
author: Daniel Rangel
status: Accepted
tags:
  - adr
  - payments
  - stripe
related_documents:
  - ../05-platform-billing/payment-processing.md
  - ../03-security/webhook-security.md
---

# ADR-003: Stripe PaymentIntents for subscription billing

## Context

Billforge needs to charge tenants for monthly and annual plans, apply proration
credits, retry failed payments and keep a complete audit trail. The application
is a Spring Boot service with a browser client. Card data must never touch the
backend.

## Decision

Use Stripe PaymentIntents as the only payment primitive for platform billing.

- The backend creates the PaymentIntent with the secret key and an idempotency
  key derived from tenant and invoice.
- The browser confirms it with Stripe.js using the returned client secret.
- The backend verifies the result by retrieving the intent and by validating
  Stripe webhooks.
- Refunds are created against the PaymentIntent ID.
- Business invoices use Stripe Checkout Sessions for hosted payment links.

## Why not an order plus client-side signature

The previous integration created a gateway order, collected a payment ID and a
client-computed signature, then verified an HMAC on the server. That model has
three problems:

1. The client sends the amount and status indirectly, so the server has to trust
   more of the request.
2. Signature verification only proves the order was created by the provider; it
   does not prove the funds were captured.
3. Every new payment method needs its own client integration.

PaymentIntents remove the client-written signature. The status comes from
Stripe, and the same intent supports cards, wallets and redirect-based methods.

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| Stripe Charges API | Legacy; no modern authentication flows |
| Stripe Checkout for subscriptions | Hides the pricing and proration logic inside Stripe |
| Stripe Subscriptions | Duplicates the local subscription model and webhook state machine |
| Multiple providers | Adds reconciliation work without a product requirement |

## Consequences

Positive:

- One source of truth for payment state: Stripe.
- Idempotency is enforced by the provider and by the database.
- The browser never needs the secret key.
- Refunds and retries use the same identifier.

Negative:

- The integration depends on Stripe's API version and SDK upgrades.
- Webhook handling is mandatory for reliable state transitions.
- A test-mode key is required to exercise the flow end to end.

## Implementation notes

- `PaymentGatewayPort` isolates Stripe behind six methods.
- `StripePaymentGateway` owns all SDK calls and error translation.
- `PaymentService` owns the state machine and audit events.
- `StripeWebhookController` verifies signatures before dispatching.
- Currency is USD and all amounts are stored in cents at the gateway boundary.
