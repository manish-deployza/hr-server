package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.pojo.NotificationPojo;

public class NotificationTestData {

    /* Public Methods */

    public static NotificationPojo aNotification(Long userId) {
        NotificationPojo n = new NotificationPojo();
        n.setUserId(userId);
        n.setType(NotificationType.LEAVE_APPLIED);
        n.setTitle("Test Notification");
        n.setMessage("Test message body");
        n.setRead(false);
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        return n;
    }
}
