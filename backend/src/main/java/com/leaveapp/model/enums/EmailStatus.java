package com.leaveapp.model.enums;

// lifecycle of one queued outbox row -- see EmailOutboxPojo / EmailSenderFlow
public enum EmailStatus {
    PENDING,
    SENT,
    FAILED
}
