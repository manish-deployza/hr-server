package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// NotificationService -> NotificationRepo -> DB (per-user in-app notification rows)
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.pojo.NotificationPojo;
import com.leaveapp.repo.NotificationRepo;

// In-app notification channel only -- no email/dispatcher, see leave-frontend's
// Prompts/notifications.md for the full trigger-matrix design; only what's actually
// called (LeaveApprovalService on final approval) is wired up so far.
@Service
public class NotificationService {

    /* Variables */

    @Autowired
    private NotificationRepo notifRepo;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<NotificationPojo> listForUser(Long userId, boolean unreadOnly) {
        return unreadOnly
            ? notifRepo.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId)
            : notifRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void notify(Long userId, NotificationType type, String title, String message,
        EntityType relEntityType, Long relEntityId) {
        NotificationPojo n = new NotificationPojo();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setRelEntityType(relEntityType);
        n.setRelatedEntityId(relEntityId);
        n.setRead(false);
        Instant now = Instant.now();
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        notifRepo.save(n);
    }

    // Idempotent -- reading an already-read notification is a no-op, not an error.
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long notificationId) {
        NotificationPojo n = notifRepo.findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> new NotFoundException("notification not found id=" + notificationId));
        if (!n.isRead()) {
            n.setRead(true);
            notifRepo.save(n);
        }
    }
}
