package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

// Plain unit test: the SMTP hop is replaced by a JavaMailSenderImpl double that captures
// the built MimeMessage instead of opening a socket.
public class MailServiceTest {

    /* Variables */

    private final CapturingSender capture = new CapturingSender();

    /* Public Methods */

    @Test
    public void constructor_configuresRelayAndEnablesAuthAndStarttls() {
        MailService svc = new MailService("smtp.example.com", 587, "user", "pw");
        JavaMailSenderImpl sender = (JavaMailSenderImpl) ReflectionTestUtils.getField(svc, "sender");

        assertEquals("smtp.example.com", sender.getHost());
        assertEquals(587, sender.getPort());
        assertEquals("user", sender.getUsername());
        assertEquals("pw", sender.getPassword());
        Properties props = sender.getJavaMailProperties();
        assertEquals("true", props.get("mail.smtp.auth"));
        assertEquals("true", props.get("mail.smtp.starttls.enable"));
    }

    @Test
    public void send_buildsMultipartMessageWithFromToSubjectAndBothBodies() throws Exception {
        MailService svc = serviceWith("noreply@test.local");

        svc.send("to@test.com", "Hello", "plain text", "<p>html</p>");

        MimeMessage msg = capture.sent;
        assertEquals("noreply@test.local", msg.getFrom()[0].toString());
        Address[] to = msg.getRecipients(Message.RecipientType.TO);
        assertEquals("to@test.com", to[0].toString());
        assertEquals("Hello", msg.getSubject());
        msg.saveChanges();
        String raw = rawContent(msg);
        assertTrue(raw.contains("plain text"));
        assertTrue(raw.contains("<p>html</p>"));
    }

    @Test
    public void send_invalidFromAddress_throwsMailParseException() {
        MailService svc = serviceWith("");

        MailParseException e = assertThrows(MailParseException.class,
            () -> svc.send("to@test.com", "Hello", "plain", "<p>html</p>"));

        assertTrue(e.getMessage().contains("to@test.com"));
        assertInstanceOf(jakarta.mail.MessagingException.class, e.getCause());
    }

    /* Private Helper Methods */

    private MailService serviceWith(String from) {
        MailService svc = new MailService("localhost", 2525, "u", "p");
        ReflectionTestUtils.setField(svc, "sender", capture);
        ReflectionTestUtils.setField(svc, "from", from);
        return svc;
    }

    private static String rawContent(MimeMessage msg) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        msg.writeTo(out);
        // multipart parts are quoted-printable/7bit; the test bodies are ASCII-safe.
        assertInstanceOf(MimeMultipart.class, msg.getContent());
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /* Test Doubles */

    private static class CapturingSender extends JavaMailSenderImpl {

        MimeMessage sent;

        @Override
        public void send(MimeMessage mimeMessage) {
            sent = mimeMessage;
        }
    }
}
