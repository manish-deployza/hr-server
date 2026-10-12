package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.UserPojo;

public class UserRepoTest extends RepositoryTestSupport {

    /* Variables */

    private static final Long ORG_1 = 1L;
    private static final Long ORG_2 = 2L;

    @Autowired
    private UserRepo userRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        UserPojo saved = userRepo.save(UserTestData.aUser(ORG_1, "a@test.com"));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByOrgIdAndEmail_returnsMatch() {
        userRepo.save(UserTestData.aUser(ORG_1, "b@test.com"));
        Optional<UserPojo> found = userRepo.findByOrgIdAndEmail(ORG_1, "b@test.com");
        assertTrue(found.isPresent());
    }

    @Test
    public void findByOrgIdAndEmail_noMatch_empty() {
        assertTrue(userRepo.findByOrgIdAndEmail(ORG_1, "nope@test.com").isEmpty());
    }

    @Test
    public void findByOrgIdAndEmail_wrongOrg_empty() {
        userRepo.save(UserTestData.aUser(ORG_1, "scoped@test.com"));
        assertTrue(userRepo.findByOrgIdAndEmail(ORG_2, "scoped@test.com").isEmpty());
    }

    @Test
    public void existsByOrgIdAndEmail_true() {
        userRepo.save(UserTestData.aUser(ORG_1, "m@test.com"));
        assertTrue(userRepo.existsByOrgIdAndEmail(ORG_1, "m@test.com"));
    }

    @Test
    public void existsByOrgIdAndEmail_wrongOrg_false() {
        userRepo.save(UserTestData.aUser(ORG_1, "n@test.com"));
        assertFalse(userRepo.existsByOrgIdAndEmail(ORG_2, "n@test.com"));
    }

    @Test
    public void existsByIdAndOrgId_true() {
        UserPojo saved = userRepo.save(UserTestData.aUser(ORG_1, "o@test.com"));
        assertTrue(userRepo.existsByIdAndOrgId(saved.getId(), ORG_1));
    }

    @Test
    public void existsByIdAndOrgId_wrongOrg_false() {
        UserPojo saved = userRepo.save(UserTestData.aUser(ORG_1, "p@test.com"));
        assertFalse(userRepo.existsByIdAndOrgId(saved.getId(), ORG_2));
    }

    @Test
    public void findByOrgId_returnsOnlyThatOrgsUsers() {
        userRepo.save(UserTestData.aUser(ORG_1, "q@test.com"));
        userRepo.save(UserTestData.aUser(ORG_2, "r@test.com"));

        List<UserPojo> found = userRepo.findByOrgId(ORG_1);

        assertEquals(1, found.size());
        assertEquals(ORG_1, found.get(0).getOrgId());
    }

    @Test
    public void findByOrgIdAndRoleIn_filtersRoleAndOrg() {
        UserPojo hr = UserTestData.aUser(ORG_1, "role-hr@test.com");
        hr.setRole(UserRole.HR);
        userRepo.save(hr);
        userRepo.save(UserTestData.aUser(ORG_1, "role-emp@test.com"));
        UserPojo otherOrgHr = UserTestData.aUser(ORG_2, "role-hr2@test.com");
        otherOrgHr.setRole(UserRole.HR);
        userRepo.save(otherOrgHr);

        List<UserPojo> found = userRepo.findByOrgIdAndRoleIn(ORG_1, List.of(UserRole.HR, UserRole.ORG_ADMIN));

        assertEquals(List.of(hr.getId()), found.stream().map(UserPojo::getId).toList());
    }

    @Test
    public void countActiveByOrgId_excludesDisabled() {
        userRepo.save(UserTestData.aUser(ORG_1, "count-active@test.com"));
        UserPojo exited = UserTestData.aUser(ORG_1, "count-disabled@test.com");
        exited.setStatus(UserStatus.DISABLED);
        userRepo.save(exited);

        assertEquals(1, userRepo.countActiveByOrgId(ORG_1));
    }

    @Test
    public void countActiveByOrgId_excludesOtherOrg() {
        userRepo.save(UserTestData.aUser(ORG_2, "count-other@test.com"));
        assertEquals(0, userRepo.countActiveByOrgId(ORG_1));
    }

    @Test
    public void save_nullOrgId_throws() {
        UserPojo u = UserTestData.aUser(ORG_1, "org-null@test.com");
        u.setOrgId(null);
        assertThrows(DataIntegrityViolationException.class, () -> userRepo.saveAndFlush(u));
    }

    @Test
    public void save_nullEmail_throws() {
        UserPojo u = UserTestData.aUser(ORG_1, "e@test.com");
        u.setEmail(null);
        assertThrows(DataIntegrityViolationException.class, () -> userRepo.saveAndFlush(u));
    }

    // passwordHash is nullable by design (onboarding redesign): an HR-created
    // employee has none until they set their own via the invite link.
    @Test
    public void save_nullPasswordHash_persists() {
        UserPojo u = UserTestData.aUser(ORG_1, "f@test.com");
        u.setPasswordHash(null);
        UserPojo saved = userRepo.saveAndFlush(u);
        assertNull(saved.getPasswordHash());
    }

    @Test
    public void save_nullRole_throws() {
        UserPojo u = UserTestData.aUser(ORG_1, "i@test.com");
        u.setRole(null);
        assertThrows(DataIntegrityViolationException.class, () -> userRepo.saveAndFlush(u));
    }

    @Test
    public void save_nullStatus_throws() {
        UserPojo u = UserTestData.aUser(ORG_1, "j@test.com");
        u.setStatus(null);
        assertThrows(DataIntegrityViolationException.class, () -> userRepo.saveAndFlush(u));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        UserPojo u = UserTestData.aUser(ORG_1, "k@test.com");
        u.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> userRepo.saveAndFlush(u));
    }

    @Test
    public void save_nullUpdatedAt_throws() {
        UserPojo u = UserTestData.aUser(ORG_1, "l@test.com");
        u.setUpdatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> userRepo.saveAndFlush(u));
    }

    @Test
    public void save_duplicateEmailSameOrg_throws() {
        userRepo.saveAndFlush(UserTestData.aUser(ORG_1, "dup@test.com"));
        assertThrows(DataIntegrityViolationException.class,
            () -> userRepo.saveAndFlush(UserTestData.aUser(ORG_1, "dup@test.com")));
    }

    @Test
    public void save_sameEmailDifferentOrg_succeeds() {
        userRepo.saveAndFlush(UserTestData.aUser(ORG_1, "shared@test.com"));
        UserPojo saved = userRepo.saveAndFlush(UserTestData.aUser(ORG_2, "shared@test.com"));
        assertNotNull(saved.getId());
    }
}
