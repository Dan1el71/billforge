package com.billforge.billing.gateway;

import com.billforge.billing.dto.PaymentIntentResult;
import com.billforge.billing.dto.PaymentLinkResult;
import com.billforge.billing.dto.RefundResult;
import com.billforge.shared.exception.PaymentException;
import com.billforge.shared.exception.ErrorCode;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Slf4j
public class StripePaymentGateway implements PaymentGatewayPort {

    @Value("${stripe.publishable-key}")
    private String publishableKey;

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.checkout.success-url:http://localhost:3000/billing?status=success}")
    private String successUrl;

    @Value("${stripe.checkout.cancel-url:http://localhost:3000/billing?status=cancelled}")
    private String cancelUrl;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
        log.info("Stripe client initialized with key: {}****",
                secretKey.substring(0, Math.min(8, secretKey.length())));
    }

    @Override
    public PaymentIntentResult createPaymentIntent(long amountCents, String currency, String receipt) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase(Locale.ROOT))
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .build())
                    .putMetadata("receipt", receipt)
                    .build();

            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(receipt)
                    .build();

            PaymentIntent intent = PaymentIntent.create(params, options);

            return PaymentIntentResult.builder()
                    .paymentIntentId(intent.getId())
                    .clientSecret(intent.getClientSecret())
                    .amount(intent.getAmount())
                    .currency(intent.getCurrency().toUpperCase(Locale.ROOT))
                    .receipt(receipt)
                    .status(intent.getStatus())
                    .publishableKey(publishableKey)
                    .build();
        } catch (StripeException e) {
            log.error("Stripe error creating PaymentIntent: {}", e.getMessage());
            throw PaymentException.paymentIntentCreationFailed(e.getMessage());
        }
    }

    @Override
    public PaymentIntentResult retrievePaymentIntent(String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            return PaymentIntentResult.builder()
                    .paymentIntentId(intent.getId())
                    .clientSecret(intent.getClientSecret())
                    .amount(intent.getAmount())
                    .currency(intent.getCurrency().toUpperCase(Locale.ROOT))
                    .receipt(intent.getMetadata() != null ? intent.getMetadata().get("receipt") : null)
                    .status(intent.getStatus())
                    .publishableKey(publishableKey)
                    .build();
        } catch (StripeException e) {
            log.warn("Stripe error retrieving PaymentIntent {}: {}", paymentIntentId, e.getMessage());
            throw PaymentException.stripeError("PAYMENT_INTENT_RETRIEVE_FAILED", e.getMessage());
        }
    }

    @Override
    public boolean verifyPayment(String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            if ("requires_capture".equals(intent.getStatus())) {
                intent = intent.capture();
            }
            return "succeeded".equals(intent.getStatus());
        } catch (StripeException e) {
            log.warn("Stripe error retrieving PaymentIntent {}: {}", paymentIntentId, e.getMessage());
            return false;
        }
    }

    @Override
    public RefundResult refundPayment(String paymentIntentId, long amountCents) {
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setAmount(amountCents)
                    .build();

            Refund refund = Refund.create(params);

            return RefundResult.builder()
                    .success("succeeded".equals(refund.getStatus()) || "pending".equals(refund.getStatus()))
                    .refundId(refund.getId())
                    .amountRefunded(refund.getAmount())
                    .failureReason("failed".equals(refund.getStatus()) ? refund.getFailureReason() : null)
                    .build();
        } catch (StripeException e) {
            log.error("Stripe error refunding PaymentIntent {}: {}", paymentIntentId, e.getMessage());
            return RefundResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .build();
        }
    }

    @Override
    public String createCustomer(String email, String name, String phone) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setEmail(email)
                    .setName(name)
                    .setPhone(phone)
                    .build();

            Customer customer = Customer.create(params);
            return customer.getId();
        } catch (StripeException e) {
            log.error("Stripe error creating customer: {}", e.getMessage());
            throw PaymentException.stripeError("CUSTOMER_CREATE_FAILED", e.getMessage());
        }
    }

    @Override
    public PaymentLinkResult createPaymentLink(long amountCents, String currency, String description,
                                               String customerEmail, String customerName,
                                               String customerPhone, String receipt) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setCustomerEmail(customerEmail)
                    .putMetadata("receipt", receipt)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(currency.toLowerCase(Locale.ROOT))
                                    .setUnitAmount(amountCents)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName(description)
                                            .build())
                                    .build())
                            .build())
                    .build();

            Session session = Session.create(params);
            log.info("Stripe Checkout Session created - id={}, receipt={}", session.getId(), receipt);

            return PaymentLinkResult.builder()
                    .id(session.getId())
                    .url(session.getUrl())
                    .build();
        } catch (StripeException e) {
            log.error("Stripe createPaymentLink failed - receipt={}: {}", receipt, e.getMessage());
            throw new PaymentException(ErrorCode.STRIPE_ERROR,
                    "Payment link creation failed: " + e.getMessage());
        }
    }
}
