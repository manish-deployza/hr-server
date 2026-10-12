package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.EmpSensitivePojo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// The P2-P4 sensitive-module table (1:1 with employee/user, PK = user_id). Every column
// except the PK and the audit timestamps is nullable -- nothing is collected at signup.
public class EmpSensitiveRepoTest extends RepositoryTestSupport {

    /* Variables */

    private static final AtomicLong UID = new AtomicLong(2000);

    @Autowired
    private EmpSensitiveRepo sensRepo;

    @PersistenceContext
    private EntityManager entityManager;

    /* Public Methods */

    @Test
    public void save_persists() {
        EmpSensitivePojo saved = sensRepo.save(EmpSensitiveTestData.aSensitiveRow(UID.incrementAndGet()));
        assertNotNull(saved.getUserId());
    }

    @Test
    public void save_bankAccountRoundTripsAsPlainText() {
        Long userId = UID.incrementAndGet();
        EmpSensitivePojo e = EmpSensitiveTestData.aSensitiveRow(userId);
        e.setBankAccount("1234567890123");
        sensRepo.saveAndFlush(e);
        entityManager.clear(); // force a genuine reload, not the first-level cache
        EmpSensitivePojo reloaded = sensRepo.findById(userId).orElseThrow();
        assertEquals("1234567890123", reloaded.getBankAccount());
    }

    // Regression test: Hibernate nulls out an @Embedded whose columns are all NULL on
    // load (true for a freshly created sensitive row, since address is never set at
    // creation), which used to NPE every caller of getAddress() -- see its override.
    @Test
    public void reload_emptyAddressComesBackNonNull() {
        Long userId = UID.incrementAndGet();
        EmpSensitivePojo e = EmpSensitiveTestData.aSensitiveRow(userId);
        sensRepo.saveAndFlush(e);
        entityManager.clear();
        EmpSensitivePojo reloaded = sensRepo.findById(userId).orElseThrow();
        assertNotNull(reloaded.getAddress());
    }

    @Test
    public void findByUserIdIn_returnsOnlyRequestedIds() {
        Long a = UID.incrementAndGet();
        Long b = UID.incrementAndGet();
        Long c = UID.incrementAndGet();
        sensRepo.save(EmpSensitiveTestData.aSensitiveRow(a));
        sensRepo.save(EmpSensitiveTestData.aSensitiveRow(b));
        sensRepo.save(EmpSensitiveTestData.aSensitiveRow(c));

        List<EmpSensitivePojo> found = sensRepo.findByUserIdIn(List.of(a, c));

        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(e -> e.getUserId().equals(a)));
        assertTrue(found.stream().anyMatch(e -> e.getUserId().equals(c)));
    }

    // No @GeneratedValue on this @Id -- a null identifier fails inside Hibernate
    // before ever reaching the DB, so it's a JpaSystemException, not a
    // DataIntegrityViolationException.
    @Test
    public void save_nullUserId_throws() {
        EmpSensitivePojo e = EmpSensitiveTestData.aSensitiveRow(UID.incrementAndGet());
        e.setUserId(null);
        assertThrows(JpaSystemException.class, () -> sensRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        EmpSensitivePojo e = EmpSensitiveTestData.aSensitiveRow(UID.incrementAndGet());
        e.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> sensRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullUpdatedAt_throws() {
        EmpSensitivePojo e = EmpSensitiveTestData.aSensitiveRow(UID.incrementAndGet());
        e.setUpdatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> sensRepo.saveAndFlush(e));
    }
}
