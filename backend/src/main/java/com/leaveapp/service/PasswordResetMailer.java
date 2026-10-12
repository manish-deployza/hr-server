package com.leaveapp.service;

// import flow: PasswordResetMailer -> JwtService (mints the link token)
//                                  -> EmpService (name for the mail subject)
//                                  -> EmailOutboxService (queues the row; EmailSenderFlow sends it later)
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.model.enums.EmailType;

/**
 * Builds and queues the "reset your password" link for the forgot-password flow.
 * Queues to email_outbox instead of sending directly -- see EmailSenderFlow -- so a
 * slow/unreachable SMTP relay never blocks the forgot-password endpoint itself, which
 * must stay fast and constant-time regardless of whether the address exists. The link
 * points straight at the frontend reset-password page ({@code app.reset.link-base}),
 * which posts the new password back to {@code POST /api/auth/reset-password}.
 */
@Service
public class PasswordResetMailer {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private EmpService employeeSvc;

    @Autowired
    private EmailOutboxService outboxSvc;

    @Autowired
    private MailTemplateService templateSvc;

    @Value("${app.reset.link-base}")
    private String linkBase;

    /* Public Methods */

    public void send(Long userId, String email) {
        String link = linkBase + "?token=" + jwtSvc.issuePasswordReset(userId);
        EmpPojo emp = employeeSvc.findByUserIdOrNull(userId);
        outboxSvc.enqueue(EmailType.PASSWORD_RESET, userId, email, subject(emp), plainBody(link), htmlBody(link));
        log.info("password reset email queued userId={}", userId);
    }

    /* Private Helper Methods */

    // Name-personalised when we have it (HR-created employees); generic otherwise.
    private static String subject(EmpPojo emp) {
        String name = emp == null ? null : emp.getName();
        return name == null || name.isBlank()
            ? "Reset your Leave account password"
            : name.trim() + ", reset your Leave account password";
    }

    private static String plainBody(String link) {
        return """
            We received a request to reset your Leave account password.

            Choose a new password here:

            %s

            This link expires in 30 minutes. If you didn't ask to reset your password you
            can safely ignore this message -- your current password still works.
            """.formatted(link);
    }

    private String htmlBody(String link) {
        return templateSvc.render("action-email", Map.of(
            "title", "Reset your password",
            "message", "We received a request to reset your Leave account password. "
                + "This button works for the next 30 minutes.",
            "buttonLabel", "Reset password",
            "link", link,
            "footnote", "If you didn't ask to reset your password, you can safely ignore this email -- "
                + "your current password still works."));
    }
}
