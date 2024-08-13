package com.billforge.shared.event.billing;

import com.billforge.shared.enums.notification.NotificationEvent;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCapturedEvent {
    private NotificationEvent eventType;
    private Long tenantId;
    private Long invoiceId;
}