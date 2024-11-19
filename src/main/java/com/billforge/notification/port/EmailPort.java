package com.billforge.notification.port;

import com.billforge.notification.provider.email.EmailMessage;

public interface EmailPort {
    void send(EmailMessage message);
}