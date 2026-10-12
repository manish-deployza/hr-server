package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

public class MailTemplateServiceTest {

    /* Variables */

    private final MailTemplateService templateSvc = new MailTemplateService();

    /* Public Methods */

    @Test
    public void render_withoutDetails_omitsDetailsBlock() {
        String html = templateSvc.render("action-email", Map.of(
            "title", "Reset your password",
            "message", "msg",
            "buttonLabel", "Reset password",
            "link", "https://app.example.com/reset?token=abc",
            "footnote", "ignore this email"));

        assertTrue(html.contains("Reset your password"));
        assertTrue(html.contains("https://app.example.com/reset?token=abc"));
        assertTrue(html.contains("ignore this email"));
        assertFalse(html.contains("padding:12px 16px;background:#f9fafb;border-radius:8px;"));
    }

    @Test
    public void render_withDetails_rendersEachLineJoinedByBr() {
        String html = templateSvc.render("action-email", Map.of(
            "title", "Verify your email address",
            "message", "msg",
            "details", "Name: Jane Doe\nEmail: jane@example.com",
            "buttonLabel", "Verify email",
            "link", "https://app.example.com/verify?token=abc",
            "footnote", "ignore this email"));

        assertTrue(html.contains("Name: Jane Doe<br>Email: jane@example.com"));
    }

    @Test
    public void render_escapesHtmlInVariables() {
        String html = templateSvc.render("action-email", Map.of(
            "title", "Hi",
            "message", "<script>alert(1)</script>",
            "buttonLabel", "Go",
            "link", "https://app.example.com",
            "footnote", "note"));

        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }
}
