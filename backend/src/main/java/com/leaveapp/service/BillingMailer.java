package com.leaveapp.service;

// import flow: BillingMailer -> UserService (ORG_ADMIN recipients for the org)
//                             -> EmailOutboxService (queues the row; EmailSenderFlow sends it later)
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.leaveapp.model.enums.EmailType;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.PlanPojo;
import com.leaveapp.pojo.UserPojo;

// Three billing notices, following the InviteMailer/VerificationMailer/
// PasswordResetMailer shape exactly (build subject + plain/HTML body, enqueue, return
// -- never calls MailService directly). Unlike those, every recipient is every
// ACTIVE/ON_NOTICE ORG_ADMIN in the org, not one specific user -- billing is an
// org-level concern, not a per-account one.
@Service
public class BillingMailer {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(BillingMailer.class);

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmailOutboxService outboxSvc;

    @Autowired
    private MailTemplateService templateSvc;

    @Value("${app.billing.portal-link-base}")
    private String portalLink;

    /* Public Methods */

    // The payment-confirmed receipt -- sent only once Razorpay's webhook confirms the
    // charge (SubscriptionService.handleActivated), never optimistically at subscribe time.
    public void sendInvoice(Long orgId, PlanPojo plan, long priceMinor, String currency, Instant periodEnd) {
        String subject = "Payment received -- " + plan.getName() + " plan";
        String message = "Your payment for the " + plan.getName() + " plan (" + formatAmount(priceMinor, currency)
            + ") has been received. Your subscription is active until " + periodEnd + ".";
        send(orgId, EmailType.INVOICE, subject, message);
    }

    // Fired the moment a subscription first enters GRACE (payment failing) -- reuses
    // the grace-period mechanism already in the state machine, not a new date column.
    public void sendRenewalWarning(Long orgId, PlanPojo plan, Instant graceUntil) {
        String subject = "Payment issue -- action needed";
        String message = "We couldn't process your latest payment for the " + plan.getName() + " plan. "
            + "Your organization will be moved to the Free plan on " + graceUntil
            + " unless this is resolved before then.";
        send(orgId, EmailType.RENEWAL_WARNING, subject, message);
    }

    // Fired the moment a downgrade to FREE actually happens -- whichever path
    // triggers it (grace-expiry scheduler, or Razorpay reporting cancelled/completed
    // directly); both route through SubscriptionService.downgradeToFree.
    public void sendDowngradeNotice(Long orgId, PlanPojo oldPlan) {
        String subject = "Your organization has moved to the Free plan";
        String message = "Your organization's " + oldPlan.getName() + " subscription has ended and you've been "
            + "moved to the Free plan. Existing employees are unaffected; new employee creation is limited to "
            + "the Free plan's seat cap until you upgrade again.";
        send(orgId, EmailType.DOWNGRADE_NOTICE, subject, message);
    }

    /* Private Helper Methods */

    private void send(Long orgId, EmailType type, String subject, String message) {
        List<UserPojo> admins = userSvc.findOrgAdminsByOrg(orgId).stream() //
            .filter(u -> UserStatus.DISABLED != u.getStatus()) //
            .toList();
        String html = templateSvc.render("action-email", Map.of( //
            "title", subject, //
            "message", message, //
            "buttonLabel", "View billing", //
            "link", portalLink, //
            "footnote", "This is an automated billing notice."));
        String plain = message + "\n\n" + portalLink;
        for (UserPojo admin : admins) {
            outboxSvc.enqueue(type, admin.getId(), admin.getEmail(), subject, plain, html);
        }
        log.info("billing email queued type={} orgId={} recipients={}", type, orgId, admins.size());
    }

    // "990000" paise -> "9900.00" -- integer minor-unit division, no float rounding.
    private static String formatAmount(long priceMinor, String currency) {
        long whole = priceMinor / 100;
        long fraction = Math.abs(priceMinor % 100);
        return currency + " " + whole + "." + (fraction < 10 ? "0" + fraction : String.valueOf(fraction));
    }
}
