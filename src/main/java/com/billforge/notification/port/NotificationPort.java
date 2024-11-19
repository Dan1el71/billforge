package com.billforge.notification.port;

import com.billforge.notification.domain.NotificationChannel;
import com.billforge.notification.domain.NotificationRequest;

public interface NotificationPort {
    NotificationChannel supportedChannel();
    void deliver(NotificationRequest request, String renderedContent);
}