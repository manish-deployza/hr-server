package com.leaveapp.model.enums;

// which mailer enqueued this row -- log/diagnostic context only, nothing branches on it
public enum EmailType {
    VERIFICATION,
    INVITE,
    APPROVAL,
    PASSWORD_RESET,
    INVOICE,
    RENEWAL_WARNING,
    DOWNGRADE_NOTICE
}
