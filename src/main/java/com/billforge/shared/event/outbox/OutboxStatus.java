package com.billforge.shared.event.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
