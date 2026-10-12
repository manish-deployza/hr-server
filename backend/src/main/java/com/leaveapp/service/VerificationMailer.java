package com.leaveapp.service;

// import flow: VerificationMailer -> JwtService (mints the link token)
//                                 -> EmpService (profile detail for the mail body)
//                                 -> EmailOutboxService (queues the row; EmailSenderFlow sends it later)
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.model.enums.EmailType;

/**
 * Builds and queues the "verify your email" link. Shared by every flow that creates a
 * user (self-service signup, HR-created employees) and by the resend-verification
 * endpoint. Queues to email_outbox instead of sending directly -- see EmailSenderFlow --
 * so a slow/unreachable SMTP relay never blocks the caller's own transaction.
 */
@Service
public class VerificationMailer {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(VerificationMailer.class);

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private EmpService employeeSvc;

    @Autowired
    private EmailOutboxService outboxSvc;

    @Autowired
    private MailTemplateService templateSvc;

    @Value("${app.verify.link-base}")
    private String linkBase;

    /* Public Methods */

    public void send(Long userId, String email) {
        String link = linkBase + "?token=" + jwtSvc.issueEmailVerify(userId);
        EmpPojo emp = employeeSvc.findByUserIdOrNull(userId);
        String details = detailLines(emp, email);
        outboxSvc.enqueue(EmailType.VERIFICATION, userId, email, subject(emp), plainBody(link, details),
            htmlBody(link, details));
        log.info("verification email queued userId={}", userId);
    }

    /* Private Helper Methods */

    // Name-personalised when we have it (HR-created employees); generic for the
    // self-signup admin whose name isn't collected at signup.
    private static String subject(EmpPojo emp) {
        String name = emp == null ? null : emp.getName();
        return name == null || name.isBlank()
            ? "Verify your email address to activate your Leave account"
            : name.trim() + ", verify your email address for Leave";
    }

    // "Label: value" per known field, blanks skipped. Same text feeds plain and HTML.
    private static String detailLines(EmpPojo emp, String email) {
        StringBuilder sb = new StringBuilder();
        if (emp != null) {
            appendLine(sb, "Name", emp.getName());
            appendLine(sb, "Employee code", emp.getEmployeeCode());
            appendLine(sb, "Designation", emp.getDesignation());
            appendLine(sb, "Department", emp.getDepartment());
        }
        appendLine(sb, "Email", email);
        return sb.toString().stripTrailing();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private static String plainBody(String link, String details) {
        return """
            Welcome!

            Confirm this email address to activate your account:

            %s

            Account details:
            %s

            This link expires in 24 hours. If you didn't create an account, you can
            safely ignore this message.
            """.formatted(link, details);
    }

    private String htmlBody(String link, String details) {
        return templateSvc.render("action-email", Map.of(
            "title", "Verify your email address",
            "message", "Confirm this email address to activate your account. "
                + "This button works for the next 24 hours.",
            "details", details,
            "buttonLabel", "Verify email",
            "link", link,
            "footnote", "If you didn't create an account, you can safely ignore this email."));
    }
}
