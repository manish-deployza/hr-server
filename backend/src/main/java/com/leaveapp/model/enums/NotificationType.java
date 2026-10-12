package com.leaveapp.model.enums;

// what a notification is about -- see NotificationService.notify's callers.
public enum NotificationType {
    LEAVE_APPLIED,
    LEAVE_APPROVED,
    LEAVE_REJECTED,
    LEAVE_CANCELLED,
    ONBOARDING_COMPLETED
}
