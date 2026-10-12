package com.leaveapp.service;

// import flow: InviteMailer -> JwtService (mints the link token)
//                           -> UserService, OrgService (org name, sender's email)
//                           -> EmpService (names for the greeting and the sender)
//                           -> EmailOutboxService (queues the row; EmailSenderFlow sends it later)
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.security.UserHelper;
import com.leaveapp.model.enums.EmailType;

/**
 * Builds and queues the onboarding invite link for an HR-created employee (no password
 * set yet). Queues to email_outbox instead of sending directly -- see EmailSenderFlow --
 * so a slow/unreachable SMTP relay never blocks the caller's own transaction. The link
 * points straight at the frontend set-password page ({@code app.invite.link-base}),
 * which posts the new password back to {@code POST /api/auth/set-password}.
 */
@Service
public class InviteMailer {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(InviteMailer.class);

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private EmpService employeeSvc;

    @Autowired
    private EmailOutboxService outboxSvc;

    @Autowired
    private MailTemplateService templateSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private OrgService orgSvc;

    @Value("${app.invite.link-base}")
    private String linkBase;

    /* Public Methods */

    public void send(Long userId, String email) {
        String link = linkBase + "?token=" + jwtSvc.issueSetPassword(userId);
        Invite inv = invite(userId);
        outboxSvc.enqueue(EmailType.INVITE, userId, email, subject(inv), plainBody(inv, link), htmlBody(inv, link));
        log.info("invite email queued userId={}", userId);
    }

    /* Private Helper Methods */

    // Who is inviting whom to what: the org name (falls back to the org key -- the name is
    // optional) and the admin sending it, so the recipient can verify the mail and reply.
    // The sender is the logged-in caller when they belong to the invitee's org; any other
    // path (no caller) falls back to the org's notify address, without a name.
    private Invite invite(Long userId) {
        UserPojo user = userSvc.findById(userId);
        OrgPojo org = orgSvc.findById(user.getOrgId());
        String orgName = hasText(org.getName()) ? org.getName().trim() : org.getOrgKey();
        String contactName = null;
        String contactEmail = org.getHrNotifyEmail();
        Long senderId = UserHelper.getUserId();
        if (senderId != null && !senderId.equals(userId)) {
            UserPojo sender = userSvc.findById(senderId);
            if (user.getOrgId().equals(sender.getOrgId())) {
                EmpPojo senderEmp = employeeSvc.findByUserIdOrNull(senderId);
                contactName = senderEmp != null && hasText(senderEmp.getName()) ? senderEmp.getName().trim() : null;
                contactEmail = sender.getEmail();
            }
        }
        EmpPojo emp = employeeSvc.findByUserIdOrNull(userId);
        String name = emp != null && hasText(emp.getName()) ? emp.getName().trim() : null;
        return new Invite(name, orgName, contactName, contactEmail);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private record Invite(String name, String orgName, String contactName, String contactEmail) {

        String greeting() {
            return name == null ? "Hi," : "Hi " + name + ",";
        }

        String contact() {
            return contactName == null ? contactEmail : contactName + " (" + contactEmail + ")";
        }

        String intro() {
            return (contactName == null ? orgName : contactName + " from " + orgName)
                + " has invited you to join " + orgName + " on Leave, where you can apply for time off and track your balances.";
        }
    }

    private static String subject(Invite inv) {
        return "You're invited to join " + inv.orgName() + " on Leave";
    }

    private static String plainBody(Invite inv, String link) {
        return """
            %s

            %s

            Set your password to activate your account (the link is valid for 7 days):

            %s

            Questions, or not sure this is meant for you? Contact %s.
            """.formatted(inv.greeting(), inv.intro(), link, inv.contact());
    }

    private String htmlBody(Invite inv, String link) {
        return templateSvc.render("action-email", Map.of(
            "title", "Join " + inv.orgName() + " on Leave",
            "message", inv.greeting() + " " + inv.intro() + " The button below is valid for 7 days.",
            "details", "Organization: " + inv.orgName() + "\nInvited by: " + inv.contact(),
            "buttonLabel", "Set your password",
            "link", link,
            "footnote", "Not expecting this? Contact " + inv.contact() + " or ignore this email."));
    }
}
