package com.billforge.shared.exception;

public class PaymentException extends BaseException {

    public PaymentException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public PaymentException(ErrorCode errorCode) {
        super(errorCode);
    }

    public static PaymentException paymentFailed(String reason) {
        return new PaymentException(ErrorCode.PAYMENT_FAILED, reason);
    }

    public static PaymentException paymentAlreadyProcessed() {
        return new PaymentException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }

    public static PaymentException invalidPaymentMethod() {
        return new PaymentException(ErrorCode.INVALID_PAYMENT_METHOD);
    }

    public static PaymentException stripeError(String code, String message) {
        return new PaymentException(ErrorCode.STRIPE_ERROR, code + ": " + message);
    }

    public static PaymentException invalidSignature() {
        return new PaymentException(ErrorCode.INVALID_PAYMENT_SIGNATURE,
                "Stripe payment verification failed");
    }

    public static PaymentException paymentIntentCreationFailed(String reason) {
        return new PaymentException(ErrorCode.PAYMENT_INTENT_CREATION_FAILED, reason);
    }
}
