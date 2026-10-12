package com.leaveapp.service;

// import flow: MailService -> Spring JavaMailSender -> angus-mail (SMTP transport)
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends mail through the configured SMTP relay (Mailjet in prod). Builds its own
 * sender from {@code app.mail.*} -- config classes declare no beans.
 */
@Service
public class MailService {

    /* Variables */

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();

    @Value("${app.mail.from}")
    private String from;

    /* Constructor */

    public MailService(
        @Value("${app.mail.host}") String host,
        @Value("${app.mail.port}") int port,
        @Value("${app.mail.username}") String username,
        @Value("${app.mail.password}") String password) {
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
    }

    /* Public Methods */

    // multipart/alternative: text-only clients get plainText, the rest render html.
    // Throws a Spring MailException (RuntimeException) on any failure -- callers that
    // must not fail on a bad SMTP hop catch it (see VerificationMailer).
    public void send(String to, String subject, String plainText, String html) {
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            sender.send(msg);
        } catch (MessagingException e) {
            throw new MailParseException("could not build message to " + to, e);
        }
    }
}
