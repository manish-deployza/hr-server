package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.NotificationData;
import com.leaveapp.pojo.NotificationPojo;
import com.leaveapp.service.NotificationService;

// Trivial single-domain read -- Controller -> Service directly, no Flow (see
// spring-backend-standards: layering allows this for a trivial passthrough).
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR', 'EMPLOYEE')")
public class NotificationController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    @Autowired
    private NotificationService notifSvc;

    /* Public Methods */

    @GetMapping("/me")
    public List<NotificationData> me(@RequestParam(required = false) Boolean unread) {
        Long userId = requireUserId();
        log.info("GET /api/notifications/me userId={} unread={}", userId, unread);
        return notifSvc.listForUser(userId, Boolean.TRUE.equals(unread)).stream()
            .map(NotificationController::toData)
            .toList();
    }

    // Per prompts/features/backend-api-design.md. 404 if the id doesn't exist or
    // belongs to another user -- same not-found-not-forbidden idiom as LeaveFlow.cancel.
    @PostMapping("/{id}/read")
    public AckData markRead(@PathVariable Long id) {
        Long userId = requireUserId();
        log.info("POST /api/notifications/{}/read userId={}", id, userId);
        notifSvc.markRead(userId, id);
        return new AckData();
    }

    /* Private Helper Methods */

    private static NotificationData toData(NotificationPojo n) {
        NotificationData data = new NotificationData();
        data.setId(n.getId());
        data.setEmployeeId(n.getUserId());
        data.setType(n.getType().name());
        data.setTitle(n.getTitle());
        data.setMessage(n.getMessage());
        data.setRelatedEntityType(n.getRelEntityType() == null ? null : n.getRelEntityType().name());
        data.setRelatedEntityId(n.getRelatedEntityId());
        data.setIsRead(n.isRead());
        data.setCreatedAt(n.getCreatedAt());
        return data;
    }
}
