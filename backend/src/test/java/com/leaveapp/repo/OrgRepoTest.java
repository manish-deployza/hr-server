package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.OrgPojo;

public class OrgRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private OrgRepo orgRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        OrgPojo saved = orgRepo.save(OrgTestData.anOrg("acme"));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByOrgKey_returnsMatch() {
        orgRepo.save(OrgTestData.anOrg("acme2"));
        Optional<OrgPojo> found = orgRepo.findByOrgKey("acme2");
        assertTrue(found.isPresent());
    }

    @Test
    public void findByOrgKey_noMatch_empty() {
        assertTrue(orgRepo.findByOrgKey("nope").isEmpty());
    }

    @Test
    public void save_nullName_succeeds() {
        OrgPojo o = OrgTestData.anOrg("t1");
        o.setName(null);
        OrgPojo saved = orgRepo.saveAndFlush(o);
        assertNotNull(saved.getId());
    }

    @Test
    public void save_nullOrgKey_throws() {
        OrgPojo o = OrgTestData.anOrg("t2");
        o.setOrgKey(null);
        assertThrows(DataIntegrityViolationException.class, () -> orgRepo.saveAndFlush(o));
    }

    @Test
    public void save_nullHrNotifyEmail_throws() {
        OrgPojo o = OrgTestData.anOrg("t3");
        o.setHrNotifyEmail(null);
        assertThrows(DataIntegrityViolationException.class, () -> orgRepo.saveAndFlush(o));
    }

    @Test
    public void save_nullStatus_throws() {
        OrgPojo o = OrgTestData.anOrg("t4");
        o.setStatus(null);
        assertThrows(DataIntegrityViolationException.class, () -> orgRepo.saveAndFlush(o));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        OrgPojo o = OrgTestData.anOrg("t5");
        o.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> orgRepo.saveAndFlush(o));
    }

    @Test
    public void save_duplicateOrgKey_throws() {
        orgRepo.saveAndFlush(OrgTestData.anOrg("dup"));
        OrgPojo dup = OrgTestData.anOrg("dup");
        assertThrows(DataIntegrityViolationException.class, () -> orgRepo.saveAndFlush(dup));
    }
}
