package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.security.UserPrincipal;

// Plain unit test. The invitee is user 10 in org 1; the "inviter" is whoever the
// SecurityContext says is logged in (user 20 in org 1, or user 30 in org 2). Every
// collaborator is a hand-written double; the real MailTemplateService renders the HTML.
public class InviteMailerTest {

    /* Variables */

    private static final Long INVITEE = 10L;
    private static final Long SAME_ORG_ADMIN = 20L;
    private static final Long OTHER_ORG_ADMIN = 30L;

    private final RecordingOutbox outbox = new RecordingOutbox();
    private final StubUserService userSvc = new StubUserService();
    private final StubOrgService orgSvc = new StubOrgService();
    private final StubEmpService empSvc = new StubEmpService();
    private final InviteMailer mailer = new InviteMailer();

    {
        ReflectionTestUtils.setField(mailer, "jwtSvc", new StubJwtService());
        ReflectionTestUtils.setField(mailer, "employeeSvc", empSvc);
        ReflectionTestUtils.setField(mailer, "outboxSvc", outbox);
        ReflectionTestUtils.setField(mailer, "templateSvc", new MailTemplateService());
        ReflectionTestUtils.setField(mailer, "userSvc", userSvc);
        ReflectionTestUtils.setField(mailer, "orgSvc", orgSvc);
        ReflectionTestUtils.setField(mailer, "linkBase", "http://app/set-password");

        userSvc.users.put(INVITEE, user(1L, "new@test.com"));
        userSvc.users.put(SAME_ORG_ADMIN, user(1L, "admin@acme.com"));
        userSvc.users.put(OTHER_ORG_ADMIN, user(2L, "admin@other.com"));
        orgSvc.orgs.put(1L, org("Acme", "acme", "hr@acme.com"));
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void send_noCallerNoProfiles_fallsBackToOrgNameAndHrNotifyEmail() {
        mailer.send(INVITEE, "new@test.com");

        EmailOutboxPojo row = outbox.only();
        assertEquals(EmailType.INVITE, row.getType());
        assertEquals(INVITEE, row.getUserId());
        assertEquals("new@test.com", row.getToAddress());
        assertEquals("You're invited to join Acme on Leave", row.getSubject());
        assertTrue(row.getPlainBody().startsWith("Hi,\n"));
        assertTrue(row.getPlainBody().contains("Acme has invited you to join Acme on Leave"));
        assertTrue(row.getPlainBody().contains("http://app/set-password?token=invite-10"));
        assertTrue(row.getPlainBody().contains("Contact hr@acme.com."));
        assertTrue(row.getHtmlBody().contains("http://app/set-password?token=invite-10"));
        assertTrue(row.getHtmlBody().contains("Invited by: hr@acme.com"));
    }

    @Test
    public void send_namedSameOrgCallerAndNamedInvitee_personalisesGreetingAndContact() {
        loginAs(SAME_ORG_ADMIN);
        empSvc.emps.put(SAME_ORG_ADMIN, emp("  Alice Admin "));
        empSvc.emps.put(INVITEE, emp(" Bob Builder "));

        mailer.send(INVITEE, "new@test.com");

        String plain = outbox.only().getPlainBody();
        assertTrue(plain.startsWith("Hi Bob Builder,\n"));
        assertTrue(plain.contains("Alice Admin from Acme has invited you to join Acme on Leave"));
        assertTrue(plain.contains("Contact Alice Admin (admin@acme.com)."));
        assertTrue(outbox.only().getHtmlBody().contains("Invited by: Alice Admin (admin@acme.com)"));
    }

    @Test
    public void send_sameOrgCallerWithoutProfile_usesCallerEmailOnly() {
        loginAs(SAME_ORG_ADMIN);

        mailer.send(INVITEE, "new@test.com");

        String plain = outbox.only().getPlainBody();
        assertTrue(plain.contains("Acme has invited you to join Acme on Leave"));
        assertTrue(plain.contains("Contact admin@acme.com."));
    }

    @Test
    public void send_sameOrgCallerWithBlankProfileName_usesCallerEmailOnly() {
        loginAs(SAME_ORG_ADMIN);
        empSvc.emps.put(SAME_ORG_ADMIN, emp("   "));
        empSvc.emps.put(INVITEE, emp("   "));

        mailer.send(INVITEE, "new@test.com");

        String plain = outbox.only().getPlainBody();
        assertTrue(plain.startsWith("Hi,\n"));
        assertTrue(plain.contains("Contact admin@acme.com."));
    }

    @Test
    public void send_sameOrgCallerWithNullProfileName_usesCallerEmailOnly() {
        loginAs(SAME_ORG_ADMIN);
        empSvc.emps.put(SAME_ORG_ADMIN, emp(null));
        empSvc.emps.put(INVITEE, emp(null));

        mailer.send(INVITEE, "new@test.com");

        String plain = outbox.only().getPlainBody();
        assertTrue(plain.startsWith("Hi,\n"));
        assertTrue(plain.contains("Contact admin@acme.com."));
    }

    @Test
    public void send_callerFromAnotherOrg_ignoredAndFallsBackToHrNotifyEmail() {
        loginAs(OTHER_ORG_ADMIN);
        empSvc.emps.put(OTHER_ORG_ADMIN, emp("Mallory"));

        mailer.send(INVITEE, "new@test.com");

        String plain = outbox.only().getPlainBody();
        assertTrue(plain.contains("Contact hr@acme.com."));
        assertFalse(plain.contains("Mallory"));
    }

    @Test
    public void send_callerIsTheInvitee_ignoredAndFallsBackToHrNotifyEmail() {
        loginAs(INVITEE);

        mailer.send(INVITEE, "new@test.com");

        assertTrue(outbox.only().getPlainBody().contains("Contact hr@acme.com."));
    }

    @Test
    public void send_orgNameBlank_fallsBackToOrgKey() {
        orgSvc.orgs.put(1L, org("   ", "acme-key", "hr@acme.com"));

        mailer.send(INVITEE, "new@test.com");

        assertEquals("You're invited to join acme-key on Leave", outbox.only().getSubject());
    }

    @Test
    public void send_orgNameNull_fallsBackToOrgKey() {
        orgSvc.orgs.put(1L, org(null, "acme-key", "hr@acme.com"));

        mailer.send(INVITEE, "new@test.com");

        assertEquals("You're invited to join acme-key on Leave", outbox.only().getSubject());
    }

    /* Private Helper Methods */

    private static void loginAs(Long userId) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    private static UserPojo user(Long orgId, String email) {
        UserPojo u = new UserPojo();
        u.setOrgId(orgId);
        u.setEmail(email);
        return u;
    }

    private static OrgPojo org(String name, String key, String hrEmail) {
        OrgPojo o = new OrgPojo();
        o.setName(name);
        o.setOrgKey(key);
        o.setHrNotifyEmail(hrEmail);
        return o;
    }

    private static EmpPojo emp(String name) {
        EmpPojo e = new EmpPojo();
        e.setStatus(EmpStatus.ACTIVE);
        e.setName(name);
        return e;
    }

    /* Test Doubles */

    private static class StubJwtService extends JwtService {

        @Override
        public String issueSetPassword(Long userId) {
            return "invite-" + userId;
        }
    }

    private static class StubUserService extends UserService {

        final Map<Long, UserPojo> users = new HashMap<>();

        @Override
        public UserPojo findById(Long id) {
            return users.get(id);
        }
    }

    private static class StubOrgService extends OrgService {

        final Map<Long, OrgPojo> orgs = new HashMap<>();

        @Override
        public OrgPojo findById(Long id) {
            return orgs.get(id);
        }
    }

    private static class StubEmpService extends EmpService {

        final Map<Long, EmpPojo> emps = new HashMap<>();

        @Override
        public EmpPojo findByUserIdOrNull(Long userId) {
            return emps.get(userId);
        }
    }

    private static class RecordingOutbox extends EmailOutboxService {

        final List<EmailOutboxPojo> rows = new ArrayList<>();

        @Override
        public EmailOutboxPojo enqueue(EmailType type, Long userId, String toAddress, String subject,
            String plainBody, String htmlBody) {
            EmailOutboxPojo row = new EmailOutboxPojo();
            row.setType(type);
            row.setUserId(userId);
            row.setToAddress(toAddress);
            row.setSubject(subject);
            row.setPlainBody(plainBody);
            row.setHtmlBody(htmlBody);
            rows.add(row);
            return row;
        }

        EmailOutboxPojo only() {
            assertEquals(1, rows.size());
            return rows.get(0);
        }
    }
}
