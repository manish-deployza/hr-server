package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;

// Gap-filler for UserServiceTest: the session-validity check (every arm, including the
// whole-second truncation), idempotent verification and the lifecycle guards.
public class UserServiceCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private UserService userSvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void findIfSessionValid_unknownUser_returnsNull() {
        assertNull(userSvc.findIfSessionValid(987654L, Instant.now()));
    }

    @Test
    public void findIfSessionValid_disabledUser_returnsNull() {
        UserPojo user = aUser("acme-userc-1", "exited@acme.test");
        user.setStatus(UserStatus.DISABLED);

        assertNull(userSvc.findIfSessionValid(user.getId(), Instant.now()));
    }

    @Test
    public void findIfSessionValid_noWatermark_returnsUser() {
        UserPojo user = aUser("acme-userc-2", "nowm@acme.test");

        assertSame(user, userSvc.findIfSessionValid(user.getId(), Instant.now()));
    }

    @Test
    public void findIfSessionValid_tokenIssuedBeforeWatermark_returnsNull() {
        UserPojo user = aUser("acme-userc-3", "stale@acme.test");
        Instant watermark = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        user.setTokensValidAfter(watermark);

        assertNull(userSvc.findIfSessionValid(user.getId(), watermark.minusSeconds(1)));
    }

    @Test
    public void findIfSessionValid_tokenIssuedAtOrAfterWatermark_returnsUser() {
        UserPojo user = aUser("acme-userc-4", "fresh@acme.test");
        Instant watermark = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        user.setTokensValidAfter(watermark);

        assertSame(user, userSvc.findIfSessionValid(user.getId(), watermark));
        assertSame(user, userSvc.findIfSessionValid(user.getId(), watermark.plusSeconds(5)));
    }

    // revoke at 12:00:00.400, re-login at 12:00:00.600 -> iat truncates to 12:00:00.000
    @Test
    public void findIfSessionValid_sameSecondReLoginAfterRevoke_isStillValid() {
        UserPojo user = aUser("acme-userc-5", "samesec@acme.test");
        Instant whole = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        user.setTokensValidAfter(whole.plusMillis(400));

        assertSame(user, userSvc.findIfSessionValid(user.getId(), whole));
    }

    @Test
    public void revokeSessions_setsWatermark_andInvalidatesOlderTokens() {
        UserPojo user = aUser("acme-userc-6", "revoke@acme.test");
        Instant issued = Instant.now().minusSeconds(10);

        userSvc.revokeSessions(user.getId());

        assertNotNull(user.getTokensValidAfter());
        assertNull(userSvc.findIfSessionValid(user.getId(), issued));
    }

    @Test
    public void revokeSessions_unknownUser_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> userSvc.revokeSessions(987654L));
    }

    @Test
    public void markVerified_firstCallVerifies_secondCallIsNoop() {
        UserPojo user = aUser("acme-userc-7", "verify@acme.test");
        assertEquals(false, user.isEmailVerified());

        userSvc.markVerified(user.getId());
        userSvc.markVerified(user.getId()); // second click on the same link: no error

        assertTrue(user.isEmailVerified());
    }

    @Test
    public void markVerified_unknownUser_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> userSvc.markVerified(987654L));
    }

    @Test
    public void activate_pendingUser_movesToActiveAndRevokesSessions() {
        UserPojo user = aUser("acme-userc-8", "onb@acme.test");
        user.setStatus(UserStatus.PENDING);

        userSvc.activate(user.getId());

        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertNotNull(user.getTokensValidAfter());
    }

    @Test
    public void activate_notPending_throwsBadRequest() {
        UserPojo user = aUser("acme-userc-9", "notpend@acme.test"); // ACTIVE

        assertThrows(BadRequestException.class, () -> userSvc.activate(user.getId()));
    }

    /* Private Helper Methods */

    private UserPojo aUser(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        return userSvc.createUser(org.getId(), email, "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.EMPLOYEE);
    }
}
