package com.leaveapp.model.data;

import java.time.Instant;

import lombok.Getter;
import lombok.Setter;

// API shape for GET /api/notifications/me -- see API-REQUIREMENTS.md "Notifications".
@Getter
@Setter
public class NotificationData {

    /* Variables */

    private Long id;
    private Long employeeId;
    private String type;
    private String title;
    private String message;
    private String relatedEntityType;
    private Long relatedEntityId;

    // Boxed on purpose: a primitive `boolean isRead` gets Lombok getter isRead(), which
    // Jackson serializes as "read" (it strips the "is" prefix) -- not the "isRead" key
    // the API contract needs. Boxed Boolean gets getIsRead() instead, which Jackson
    // serializes correctly as "isRead" (see SessionData.isManager for the same fix).
    private Boolean isRead;

    private Instant createdAt;
}
