package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.NotificationPojo;

public class NotificationRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private NotificationRepo notificationRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        NotificationPojo saved = notificationRepo.save(NotificationTestData.aNotification(1L));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByUserIdAndReadFalseOrderByCreatedAtDesc_returnsUnread() {
        notificationRepo.save(NotificationTestData.aNotification(1L));
        List<NotificationPojo> found = notificationRepo.findByUserIdAndReadFalseOrderByCreatedAtDesc(1L);
        assertEquals(1, found.size());
    }

    @Test
    public void findByUserIdAndReadFalseOrderByCreatedAtDesc_excludesRead() {
        NotificationPojo n = NotificationTestData.aNotification(1L);
        n.setRead(true);
        notificationRepo.save(n);
        assertTrue(notificationRepo.findByUserIdAndReadFalseOrderByCreatedAtDesc(1L).isEmpty());
    }

    @Test
    public void findByUserIdOrderByCreatedAtDesc_newestFirst() {
        NotificationPojo older = NotificationTestData.aNotification(901L);
        older.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        NotificationPojo newer = NotificationTestData.aNotification(901L);
        newer.setRead(true);
        notificationRepo.save(older);
        notificationRepo.save(newer);
        notificationRepo.save(NotificationTestData.aNotification(902L));

        List<NotificationPojo> found = notificationRepo.findByUserIdOrderByCreatedAtDesc(901L);

        assertEquals(List.of(newer.getId(), older.getId()), found.stream().map(NotificationPojo::getId).toList());
    }

    @Test
    public void findByIdAndUserId_returnsOwn() {
        NotificationPojo saved = notificationRepo.save(NotificationTestData.aNotification(903L));
        assertTrue(notificationRepo.findByIdAndUserId(saved.getId(), 903L).isPresent());
    }

    @Test
    public void findByIdAndUserId_otherUser_empty() {
        NotificationPojo saved = notificationRepo.save(NotificationTestData.aNotification(904L));
        assertTrue(notificationRepo.findByIdAndUserId(saved.getId(), 905L).isEmpty());
    }

    @Test
    public void save_nullUserId_throws() {
        NotificationPojo n = NotificationTestData.aNotification(1L);
        n.setUserId(null);
        assertThrows(DataIntegrityViolationException.class, () -> notificationRepo.saveAndFlush(n));
    }

    @Test
    public void save_nullType_throws() {
        NotificationPojo n = NotificationTestData.aNotification(1L);
        n.setType(null);
        assertThrows(DataIntegrityViolationException.class, () -> notificationRepo.saveAndFlush(n));
    }

    @Test
    public void save_nullTitle_throws() {
        NotificationPojo n = NotificationTestData.aNotification(1L);
        n.setTitle(null);
        assertThrows(DataIntegrityViolationException.class, () -> notificationRepo.saveAndFlush(n));
    }

    @Test
    public void save_nullMessage_throws() {
        NotificationPojo n = NotificationTestData.aNotification(1L);
        n.setMessage(null);
        assertThrows(DataIntegrityViolationException.class, () -> notificationRepo.saveAndFlush(n));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        NotificationPojo n = NotificationTestData.aNotification(1L);
        n.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> notificationRepo.saveAndFlush(n));
    }
}
