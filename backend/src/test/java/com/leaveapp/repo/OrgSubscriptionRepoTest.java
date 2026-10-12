package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.SubscriptionStatus;
import com.leaveapp.pojo.OrgSubscriptionPojo;

public class OrgSubscriptionRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private OrgSubscriptionRepo subRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        OrgSubscriptionPojo saved = subRepo.save(OrgSubscriptionTestData.aSubscription(1L, 1L));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByOrgId_returnsMatch() {
        subRepo.save(OrgSubscriptionTestData.aSubscription(2L, 1L));
        assertTrue(subRepo.findByOrgId(2L).isPresent());
    }

    @Test
    public void findByOrgId_noMatch_empty() {
        assertTrue(subRepo.findByOrgId(999_999L).isEmpty());
    }

    @Test
    public void findByRazorpaySubscriptionId_returnsMatch() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(3L, 1L);
        s.setRazorpaySubscriptionId("sub_abc");
        subRepo.save(s);
        assertTrue(subRepo.findByRazorpaySubscriptionId("sub_abc").isPresent());
    }

    @Test
    public void findReconcilable_includesMatchingStatusWithRazorpayId() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(4L, 1L);
        s.setStatus(SubscriptionStatus.PENDING);
        s.setRazorpaySubscriptionId("sub_rec");
        subRepo.saveAndFlush(s);
        List<OrgSubscriptionPojo> found = subRepo.findReconcilable(
            EnumSet.of(SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE, SubscriptionStatus.GRACE),
            PageRequest.of(0, 20));
        assertTrue(found.stream().anyMatch(x -> "sub_rec".equals(x.getRazorpaySubscriptionId())));
    }

    @Test
    public void findReconcilable_excludesRowsWithNoRazorpayId() {
        subRepo.saveAndFlush(OrgSubscriptionTestData.aSubscription(5L, 1L)); // FREE, no razorpay id
        List<OrgSubscriptionPojo> found = subRepo.findReconcilable(
            EnumSet.of(SubscriptionStatus.ACTIVE), PageRequest.of(0, 20));
        assertTrue(found.stream().noneMatch(x -> x.getOrgId().equals(5L)));
    }

    @Test
    public void findGraceExpired_includesLapsedGrace() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(6L, 1L);
        s.setStatus(SubscriptionStatus.GRACE);
        s.setGraceUntil(Instant.now().minus(1, ChronoUnit.DAYS));
        subRepo.saveAndFlush(s);
        List<OrgSubscriptionPojo> found = subRepo.findGraceExpired(Instant.now(), PageRequest.of(0, 20));
        assertTrue(found.stream().anyMatch(x -> x.getOrgId().equals(6L)));
    }

    @Test
    public void findGraceExpired_excludesFutureGrace() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(7L, 1L);
        s.setStatus(SubscriptionStatus.GRACE);
        s.setGraceUntil(Instant.now().plus(1, ChronoUnit.DAYS));
        subRepo.saveAndFlush(s);
        List<OrgSubscriptionPojo> found = subRepo.findGraceExpired(Instant.now(), PageRequest.of(0, 20));
        assertTrue(found.stream().noneMatch(x -> x.getOrgId().equals(7L)));
    }

    @Test
    public void save_nullOrgId_throws() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(8L, 1L);
        s.setOrgId(null);
        assertThrows(DataIntegrityViolationException.class, () -> {
            subRepo.save(s);
            subRepo.flush();
        });
    }

    @Test
    public void save_nullPlanId_throws() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(14L, 1L);
        s.setPlanId(null);
        assertThrows(DataIntegrityViolationException.class, () -> subRepo.saveAndFlush(s));
    }

    @Test
    public void save_nullStatus_throws() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(15L, 1L);
        s.setStatus(null);
        assertThrows(DataIntegrityViolationException.class, () -> subRepo.saveAndFlush(s));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(16L, 1L);
        s.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> subRepo.saveAndFlush(s));
    }

    @Test
    public void save_nullUpdatedAt_throws() {
        OrgSubscriptionPojo s = OrgSubscriptionTestData.aSubscription(17L, 1L);
        s.setUpdatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> subRepo.saveAndFlush(s));
    }

    @Test
    public void save_duplicateOrgId_throws() {
        subRepo.saveAndFlush(OrgSubscriptionTestData.aSubscription(9L, 1L));
        OrgSubscriptionPojo dup = OrgSubscriptionTestData.aSubscription(9L, 1L);
        assertThrows(DataIntegrityViolationException.class, () -> subRepo.saveAndFlush(dup));
    }

    @Test
    public void save_duplicateRazorpaySubscriptionId_throws() {
        OrgSubscriptionPojo s1 = OrgSubscriptionTestData.aSubscription(10L, 1L);
        s1.setRazorpaySubscriptionId("sub_dup");
        subRepo.saveAndFlush(s1);
        OrgSubscriptionPojo s2 = OrgSubscriptionTestData.aSubscription(11L, 1L);
        s2.setRazorpaySubscriptionId("sub_dup");
        assertThrows(DataIntegrityViolationException.class, () -> subRepo.saveAndFlush(s2));
    }

    @Test
    public void save_nullRazorpaySubscriptionId_multipleAllowed() {
        subRepo.saveAndFlush(OrgSubscriptionTestData.aSubscription(12L, 1L));
        subRepo.saveAndFlush(OrgSubscriptionTestData.aSubscription(13L, 1L));
    }
}
