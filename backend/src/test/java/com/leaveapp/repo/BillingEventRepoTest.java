package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.BillingEventPojo;

public class BillingEventRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private BillingEventRepo eventRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        BillingEventPojo saved = eventRepo.save(BillingEventTestData.anEvent("evt_save"));
        assertNotNull(saved.getId());
    }

    @Test
    public void existsByRazorpayEventId_true() {
        eventRepo.save(BillingEventTestData.anEvent("evt_exists"));
        assertTrue(eventRepo.existsByRazorpayEventId("evt_exists"));
    }

    @Test
    public void existsByRazorpayEventId_false() {
        assertFalse(eventRepo.existsByRazorpayEventId("evt_missing"));
    }

    @Test
    public void save_nullEventId_throws() {
        BillingEventPojo e = BillingEventTestData.anEvent("evt_null");
        e.setRazorpayEventId(null);
        assertThrows(DataIntegrityViolationException.class, () -> {
            eventRepo.save(e);
            eventRepo.flush();
        });
    }

    @Test
    public void save_duplicateEventId_throws() {
        eventRepo.saveAndFlush(BillingEventTestData.anEvent("evt_dup"));
        BillingEventPojo dup = BillingEventTestData.anEvent("evt_dup");
        assertThrows(DataIntegrityViolationException.class, () -> eventRepo.saveAndFlush(dup));
    }

    @Test
    public void save_nullEventType_throws() {
        BillingEventPojo e = BillingEventTestData.anEvent("evt_null_type");
        e.setEventType(null);
        assertThrows(DataIntegrityViolationException.class, () -> eventRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullProcessedAt_throws() {
        BillingEventPojo e = BillingEventTestData.anEvent("evt_null_processed");
        e.setProcessedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> eventRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        BillingEventPojo e = BillingEventTestData.anEvent("evt_null_created");
        e.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> eventRepo.saveAndFlush(e));
    }
}
