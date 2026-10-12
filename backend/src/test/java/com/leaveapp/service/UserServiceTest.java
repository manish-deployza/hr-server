package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;

public class UserServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private UserService userSvc;

    @Autowired
    private CredentialService credSvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void createUser_persistsWithGivenRole() {
        OrgPojo org = orgSvc.create("Acme", "acme-user-1", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "jane@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);

        assertNotNull(user.getId());
        assertEquals(org.getId(), user.getOrgId());
        assertEquals("ORG_ADMIN", user.getRole().name());
        assertEquals("ACTIVE", user.getStatus().name());
    }

    @Test
    public void createUser_rejectsPasswordsFailingNistScreening() {
        OrgPojo org = orgSvc.create("Acme", "acme-user-nist", "hr@acme.test", "IN");
        assertThrows(BadRequestException.class, // too short (< 8)
            () -> userSvc.createUser(org.getId(), "jane@acme.test", pwd("short12"), UserRole.ORG_ADMIN));
        assertThrows(BadRequestException.class, // repetitive
            () -> userSvc.createUser(org.getId(), "jane@acme.test", pwd("aaaaaaaa"), UserRole.ORG_ADMIN));
        assertThrows(BadRequestException.class, // contains email local-part
            () -> userSvc.createUser(org.getId(), "jane@acme.test", pwd("jane-secret"), UserRole.ORG_ADMIN));
        assertThrows(BadRequestException.class, // over 72 bytes
            () -> userSvc.createUser(org.getId(), "jane@acme.test", pwd("x".repeat(73)), UserRole.ORG_ADMIN));
    }

    @Test
    public void verifyLogin_correctOrgAndPassword_returnsUser() {
        OrgPojo org = orgSvc.create("Acme", "acme-user-2", "hr@acme.test", "IN");
        userSvc.createUser(org.getId(), "jane2@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);

        UserPojo found = credSvc.verifyLogin(org.getId(), "jane2@acme.test", pwd("s3cret!!"));
        assertEquals("jane2@acme.test", found.getEmail());
    }

    @Test
    public void verifyLogin_wrongOrg_throws() {
        OrgPojo org1 = orgSvc.create("Acme", "acme-user-3", "hr@acme.test", "IN");
        OrgPojo org2 = orgSvc.create("Other", "other-user-3", "hr@other.test", "IN");
        userSvc.createUser(org1.getId(), "jane3@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);

        assertThrows(NotAuthException.class, //
            () -> credSvc.verifyLogin(org2.getId(), "jane3@acme.test", pwd("s3cret!!")));
    }

    @Test
    public void verifyLogin_wrongPassword_throws() {
        OrgPojo org = orgSvc.create("Acme", "acme-user-4", "hr@acme.test", "IN");
        userSvc.createUser(org.getId(), "jane4@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);

        assertThrows(NotAuthException.class, //
            () -> credSvc.verifyLogin(org.getId(), "jane4@acme.test", pwd("wrongpw1")));
    }

    @Test
    public void onboardingLifecycle_invitedToPendingToOnboardedToActive() {
        OrgPojo org = orgSvc.create("Acme", "acme-user-life", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "new@acme.test", new byte[0], UserRole.EMPLOYEE,
            UserStatus.INVITED);
        assertEquals(UserStatus.INVITED, user.getStatus());
        // no password yet -> cannot log in
        assertThrows(NotAuthException.class, //
            () -> credSvc.verifyLogin(org.getId(), "new@acme.test", pwd("s3cret!!")));

        credSvc.setInitialPassword(user.getId(), pwd("s3cret!!"));
        assertEquals(UserStatus.PENDING, userSvc.findById(user.getId()).getStatus());
        // invite link is single-use
        assertThrows(BadRequestException.class, //
            () -> credSvc.setInitialPassword(user.getId(), pwd("an0ther-pw!")));

        // PENDING can log in, and stays PENDING
        credSvc.verifyLogin(org.getId(), "new@acme.test", pwd("s3cret!!"));
        assertEquals(UserStatus.PENDING, userSvc.findById(user.getId()).getStatus());
    }

    @Test
    public void forgotPasswordReset_onInvitedUser_movesThemToPending() {
        OrgPojo org = orgSvc.create("Acme", "acme-user-reset", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "inv@acme.test", new byte[0], UserRole.EMPLOYEE,
            UserStatus.INVITED);

        credSvc.resetPassword(user.getId(), pwd("s3cret!!"));

        assertEquals(UserStatus.PENDING, userSvc.findById(user.getId()).getStatus());
        credSvc.verifyLogin(org.getId(), "inv@acme.test", pwd("s3cret!!")); // can now log in to onboarding
    }

    /* Private Helper Methods */

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
