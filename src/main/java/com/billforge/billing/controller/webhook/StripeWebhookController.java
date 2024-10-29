package com.billforge.billing.controller.webhook;

import com.billforge.shared.exception.PaymentException;
import com.billforge.billing.service.PaymentService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles inbound Stripe webhook events.
 *
 * Security model:
 *  - This endpoint is PUBLIC (no JWT) - Stripe calls it from its servers.
 *  - Every request is authenticated with the Stripe-Signature header before
 *    any business logic runs.
 *  - Returning 200 OK tells Stripe the event was received. Any non-2xx
 *    causes Stripe to retry with exponential backoff.
 *
 * Events handled:
 *  - payment_intent.succeeded       -> handlePaymentSuccess
 *  - payment_intent.payment_failed  -> handlePaymentFailure
 *  - All other events               -> acknowledged but ignored (returns 200)
 */
@RestController
@RequestMapping("/api/${api.version}/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Stripe inbound webhook receiver")
public class StripeWebhookController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentService paymentService;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    // ── POST /api/webhooks/stripe ─────────────────────────────────────────────

    @PostMapping("/stripe")
    @Operation(
            summary = "Stripe webhook receiver",
            description = "Receives and processes Stripe webhook events. " +
                    "Authenticated with the Stripe-Signature header. " +
                    "Always returns 200 OK on signature validation success, regardless of event type."
    )
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature) {

        Event event = verifySignature(payload, signature);
        String eventType = event.getType();

        log.info("Stripe webhook received: event={}", eventType);

        try {
            JsonNode object = MAPPER.readTree(payload).path("data").path("object");

            switch (eventType) {
                case "payment_intent.succeeded" -> {
                    String paymentIntentId = object.path("id").asText();
                    paymentService.handlePaymentSuccess(paymentIntentId);
                    log.info("Payment succeeded handled: paymentIntentId={}", paymentIntentId);
                }
                case "payment_intent.payment_failed" -> {
                    String paymentIntentId = object.path("id").asText();
                    JsonNode error = object.path("last_payment_error");
                    String failureCode = error.path("code").asText("UNKNOWN");
                    String failureMsg = error.path("message").asText("Payment failed");
                    paymentService.handlePaymentFailure(paymentIntentId, failureCode, failureMsg);
                    log.info("Payment failure handled: paymentIntentId={}, code={}", paymentIntentId, failureCode);
                }
                default -> log.debug("Unhandled Stripe event type: {}", eventType);
            }
        } catch (Exception e) {
            // Log but still return 200 - prevents Stripe from retrying events
            // we received but failed to process internally.
            log.error("Error processing Stripe event={}: {}", eventType, e.getMessage(), e);
        }

        return ResponseEntity.ok("ok");
    }

    private Event verifySignature(String payload, String signature) {
        try {
            return Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification FAILED");
            throw PaymentException.invalidSignature();
        } catch (Exception e) {
            log.error("Stripe signature verification error: {}", e.getMessage());
            throw PaymentException.invalidSignature();
        }
    }
}
