package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.NotificationData;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.pojo.NotificationPojo;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.NotificationService;

// Plain unit test, same style as AdminUserControllerTest: the three real roles get in,
// everything else (onboarding, unknown, anonymous) is rejected; the caller's own id from
// the token is what reaches the service, and the pojo -> data mapping is checked for both
// the with-entity and no-entity shapes.
public class NotificationControllerTest {

    /* Variables */

    private final RecordingSvc svc = new RecordingSvc();
    private final NotificationController target = new NotificationController();
    private final NotificationController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "notifSvc", svc);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void me_unreadNull_listsAll() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertTrue(controller.me(null).isEmpty());
        assertEquals(List.of("list:5:false"), svc.log);
    }

    @Test
    public void me_unreadFalse_listsAll() {
        authenticate(5L, "ROLE_EMPLOYEE");
        controller.me(false);
        assertEquals(List.of("list:5:false"), svc.log);
    }

    @Test
    public void me_unreadTrue_listsUnreadOnly() {
        authenticate(5L, "ROLE_EMPLOYEE");
        controller.me(true);
        assertEquals(List.of("list:5:true"), svc.log);
    }

    @Test
    public void me_mapsPojoWithRelatedEntity() {
        authenticate(5L, "ROLE_EMPLOYEE");
        NotificationPojo n = pojo(EntityType.LEAVE_REQUEST, 42L);
        svc.rows.add(n);

        List<NotificationData> out = controller.me(null);

        assertEquals(1, out.size());
        NotificationData d = out.get(0);
        assertEquals(11L, d.getId());
        assertEquals(5L, d.getEmployeeId());
        assertEquals("LEAVE_APPROVED", d.getType());
        assertEquals("title", d.getTitle());
        assertEquals("msg", d.getMessage());
        assertEquals("LEAVE_REQUEST", d.getRelatedEntityType());
        assertEquals(42L, d.getRelatedEntityId());
        assertEquals(true, d.getIsRead());
        assertEquals(n.getCreatedAt(), d.getCreatedAt());
    }

    @Test
    public void me_mapsPojoWithoutRelatedEntity() {
        authenticate(5L, "ROLE_EMPLOYEE");
        svc.rows.add(pojo(null, null));

        NotificationData d = controller.me(null).get(0);

        assertNull(d.getRelatedEntityType());
        assertNull(d.getRelatedEntityId());
    }

    @Test
    public void markRead_passesOwnUserIdAndNotificationId() {
        authenticate(5L, "ROLE_HR");
        assertNotNull(controller.markRead(11L));
        assertEquals(List.of("read:5:11"), svc.log);
    }

    @Test
    public void markRead_serviceNotFound_propagates() {
        authenticate(5L, "ROLE_HR");
        svc.fail = true;
        assertThrows(NotFoundException.class, () -> controller.markRead(11L));
    }

    @Test
    public void allowedRoles_pass() {
        for (UserRole role : List.of(UserRole.ORG_ADMIN, UserRole.HR, UserRole.EMPLOYEE)) {
            authenticate(5L, "ROLE_" + role.name());
            controller.me(null);
            controller.markRead(1L);
        }
        assertEquals(6, svc.log.size());
    }

    @Test
    public void onboardingAndUnknownRoles_forbidden() {
        for (String authority : List.of("ROLE_ONBOARDING", "ROLE_SOMETHING_ELSE")) {
            authenticate(5L, authority);
            assertThrows(AccessDeniedException.class, () -> controller.me(null));
            assertThrows(AccessDeniedException.class, () -> controller.markRead(1L));
        }
        assertTrue(svc.log.isEmpty());
    }

    @Test
    public void anonymous_rejected() {
        assertThrows(Exception.class, () -> controller.me(null));
        assertThrows(Exception.class, () -> controller.markRead(1L));
    }

    @Test
    public void principalWithoutUserId_notAuthenticated() {
        authenticate(null, "ROLE_EMPLOYEE");
        assertThrows(NotAuthException.class, () -> controller.me(null));
        assertThrows(NotAuthException.class, () -> controller.markRead(1L));
    }

    /* Private Helper Methods */

    private static NotificationPojo pojo(EntityType relType, Long relId) {
        NotificationPojo n = new NotificationPojo();
        n.setId(11L);
        n.setUserId(5L);
        n.setType(NotificationType.LEAVE_APPROVED);
        n.setTitle("title");
        n.setMessage("msg");
        n.setRelEntityType(relType);
        n.setRelatedEntityId(relId);
        n.setRead(true);
        n.setCreatedAt(Instant.parse("2026-01-02T03:04:05Z"));
        return n;
    }

    private static void authenticate(Long userId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(1L);
        p.setRoles(List.of(UserRole.EMPLOYEE));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class RecordingSvc extends NotificationService {
        private final List<String> log = new ArrayList<>();
        private final List<NotificationPojo> rows = new ArrayList<>();
        private boolean fail;

        @Override
        public List<NotificationPojo> listForUser(Long userId, boolean unreadOnly) {
            log.add("list:" + userId + ":" + unreadOnly);
            return rows;
        }

        @Override
        public void markRead(Long userId, Long notificationId) {
            log.add("read:" + userId + ":" + notificationId);
            if (fail) {
                throw new NotFoundException("notification not found id=" + notificationId);
            }
        }
    }
}
