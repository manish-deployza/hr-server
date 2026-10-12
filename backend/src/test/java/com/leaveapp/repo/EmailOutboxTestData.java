package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.model.enums.EmailStatus;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.pojo.EmailOutboxPojo;

public class EmailOutboxTestData {

    /* Public Methods */

    public static EmailOutboxPojo anEmail(EmailStatus status, Instant createdAt) {
        EmailOutboxPojo e = new EmailOutboxPojo();
        e.setType(EmailType.INVITE);
        e.setToAddress("to@test.com");
        e.setSubject("Test Subject");
        e.setPlainBody("plain");
        e.setHtmlBody("<p>html</p>");
        e.setStatus(status);
        e.setAttempts(0);
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        return e;
    }
}
