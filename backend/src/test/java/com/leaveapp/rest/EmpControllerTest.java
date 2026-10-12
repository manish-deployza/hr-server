package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.model.data.PhotoData;
import com.leaveapp.model.data.SessionData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.ProfileForm;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.EmpFlow;

// Plain unit test, same style as AdminUserControllerTest: the three real roles get in,
// every other authority (onboarding, unknown, anonymous) is rejected, and the caller's own
// id from the token is what reaches the Flow. Photo upload covers an unreadable upload
// (400) and photo download covers the content-type / length / body written to the response.
public class EmpControllerTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
    private final EmpController target = new EmpController();
    private final EmpController controller = SecurityProxyTestSupport.withPreAuthorize(target);
    private final MockHttpServletResponse res = new MockHttpServletResponse();

    {
        ReflectionTestUtils.setField(target, "empFlow", flow);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void me_returnsProfileOfOwnUserId() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertSame(flow.returns, controller.me());
        assertEquals(List.of("getProfile:5"), flow.log);
    }

    @Test
    public void updateMe_passesOwnUserIdAndForm() {
        authenticate(5L, "ROLE_EMPLOYEE");
        ProfileForm form = new ProfileForm();
        form.setPhone("123");

        assertSame(flow.returns, controller.updateMe(form));
        assertEquals(List.of("updateProfile:5:123"), flow.log);
    }

    @Test
    public void uploadPhoto_passesBytesAndContentType() {
        authenticate(5L, "ROLE_EMPLOYEE");
        MockMultipartFile photo = new MockMultipartFile("photo", "me.png", "image/png", new byte[] { 1, 2, 3 });

        assertSame(flow.returns, controller.uploadPhoto(photo));
        assertEquals(List.of("uploadPhoto:5:3:image/png"), flow.log);
    }

    @Test
    public void uploadPhoto_unreadableUpload_badRequest() {
        authenticate(5L, "ROLE_EMPLOYEE");
        MockMultipartFile broken = new MockMultipartFile("photo", "me.png", "image/png", new byte[] { 1 }) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("disk gone");
            }
        };

        assertThrows(BadRequestException.class, () -> controller.uploadPhoto(broken));
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void getPhoto_writesBytesWithContentTypeAndLength() throws IOException {
        authenticate(5L, "ROLE_EMPLOYEE");
        flow.photo.setBytes(new byte[] { 9, 8, 7, 6 });
        flow.photo.setContentType("image/jpeg");

        controller.getPhoto(res);

        assertEquals("image/jpeg", res.getContentType());
        assertEquals(4, res.getContentLength());
        assertArrayEquals(new byte[] { 9, 8, 7, 6 }, res.getContentAsByteArray());
        assertEquals(List.of("getPhoto:5"), flow.log);
    }

    @Test
    public void deletePhoto_passesOwnUserId() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertSame(flow.returns, controller.deletePhoto());
        assertEquals(List.of("deletePhoto:5"), flow.log);
    }

    @Test
    public void allowedRoles_pass() {
        for (UserRole role : List.of(UserRole.ORG_ADMIN, UserRole.HR, UserRole.EMPLOYEE)) {
            authenticate(5L, "ROLE_" + role.name());
            controller.me();
        }
        assertEquals(3, flow.log.size());
    }

    @Test
    public void onboardingAndUnknownRoles_forbidden() {
        for (String authority : List.of("ROLE_ONBOARDING", "ROLE_SOMETHING_ELSE")) {
            authenticate(5L, authority);
            assertThrows(AccessDeniedException.class, controller::me);
            assertThrows(AccessDeniedException.class, () -> controller.updateMe(new ProfileForm()));
            assertThrows(AccessDeniedException.class, () -> controller.uploadPhoto(null));
            assertThrows(AccessDeniedException.class, () -> controller.getPhoto(res));
            assertThrows(AccessDeniedException.class, controller::deletePhoto);
        }
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void anonymous_rejected() {
        assertThrows(Exception.class, controller::me);
        assertThrows(Exception.class, controller::deletePhoto);
    }

    @Test
    public void principalWithoutUserId_notAuthenticated() {
        authenticate(null, "ROLE_EMPLOYEE");
        assertThrows(NotAuthException.class, controller::me);
        assertThrows(NotAuthException.class, () -> controller.updateMe(new ProfileForm()));
        assertThrows(NotAuthException.class, () -> controller.uploadPhoto(null));
        assertThrows(NotAuthException.class, () -> controller.getPhoto(res));
        assertThrows(NotAuthException.class, controller::deletePhoto);
        assertTrue(flow.log.isEmpty());
    }

    /* Private Helper Methods */

    private static void authenticate(Long userId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(1L);
        p.setRoles(List.of(UserRole.EMPLOYEE));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class RecordingFlow extends EmpFlow {
        private final List<String> log = new ArrayList<>();
        private final SessionData returns = new SessionData();
        private final PhotoData photo = new PhotoData();

        @Override
        public SessionData getProfile(Long userId) {
            log.add("getProfile:" + userId);
            return returns;
        }

        @Override
        public SessionData updateProfile(Long userId, ProfileForm form) {
            log.add("updateProfile:" + userId + ":" + form.getPhone());
            return returns;
        }

        @Override
        public SessionData uploadPhoto(Long userId, byte[] bytes, String contentType) {
            log.add("uploadPhoto:" + userId + ":" + bytes.length + ":" + contentType);
            return returns;
        }

        @Override
        public PhotoData getPhoto(Long userId) {
            log.add("getPhoto:" + userId);
            return photo;
        }

        @Override
        public SessionData deletePhoto(Long userId) {
            log.add("deletePhoto:" + userId);
            return returns;
        }
    }
}
