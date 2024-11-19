package com.billforge.billing.dto;

import com.billforge.shared.enums.billing.PaymentMethod;
import com.billforge.shared.enums.billing.PaymentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private Long id;
    private Long invoiceId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private PaymentMethod paymentMethod;
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String failureCode;
    private String failureMessage;
    private Integer retryCount;
    private Instant nextRetryAt;
    private Instant paidAt;
    private Instant createdAt;
}
