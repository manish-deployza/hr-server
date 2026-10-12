package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.pojo.NotificationPojo;

// In-app notifications: create, list (all / unread only) and idempotent, ownership-scoped mark-read.
public class NotificationServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private NotificationService notifSvc;

    /* Public Methods */

    @Test
    public void notify_persistsUnreadNotificationWithRelatedEntity() {
        notifSvc.notify(1L, NotificationType.LEAVE_APPLIED, "Title", "Body", EntityType.LEAVE_REQUEST, 42L);

        NotificationPojo n = notifSvc.listForUser(1L, false).get(0);
        assertEquals(NotificationType.LEAVE_APPLIED, n.getType());
        assertEquals("Title", n.getTitle());
        assertEquals("Body", n.getMessage());
        assertEquals(EntityType.LEAVE_REQUEST, n.getRelEntityType());
        assertEquals(42L, n.getRelatedEntityId());
        assertFalse(n.isRead());
    }

    @Test
    public void listForUser_unreadOnlyFiltersReadOnes_andIsScopedToUser() {
        notifSvc.notify(2L, NotificationType.LEAVE_APPLIED, "a", "a", EntityType.LEAVE_REQUEST, 1L);
        notifSvc.notify(2L, NotificationType.LEAVE_APPROVED, "b", "b", EntityType.LEAVE_REQUEST, 2L);
        notifSvc.notify(3L, NotificationType.LEAVE_APPROVED, "c", "c", EntityType.LEAVE_REQUEST, 3L);
        List<NotificationPojo> all = notifSvc.listForUser(2L, false);
        notifSvc.markRead(2L, all.get(0).getId());

        assertEquals(2, notifSvc.listForUser(2L, false).size());
        assertEquals(1, notifSvc.listForUser(2L, true).size());
        assertEquals(1, notifSvc.listForUser(3L, true).size());
        assertTrue(notifSvc.listForUser(4L, false).isEmpty());
    }

    @Test
    public void markRead_unread_becomesRead_andRepeatIsNoOp() {
        notifSvc.notify(5L, NotificationType.LEAVE_APPLIED, "a", "a", EntityType.LEAVE_REQUEST, 1L);
        Long id = notifSvc.listForUser(5L, false).get(0).getId();

        notifSvc.markRead(5L, id);
        notifSvc.markRead(5L, id);

        assertTrue(notifSvc.listForUser(5L, false).get(0).isRead());
    }

    @Test
    public void markRead_unknownOrAnotherUsersNotification_notFound() {
        notifSvc.notify(6L, NotificationType.LEAVE_APPLIED, "a", "a", EntityType.LEAVE_REQUEST, 1L);
        Long id = notifSvc.listForUser(6L, false).get(0).getId();

        assertThrows(NotFoundException.class, () -> notifSvc.markRead(6L, -1L));
        assertThrows(NotFoundException.class, () -> notifSvc.markRead(7L, id));
        assertFalse(notifSvc.listForUser(6L, false).get(0).isRead());
    }
}
