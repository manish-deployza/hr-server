package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.pebbletemplates.pebble.template.PebbleTemplate;

// A write failure surfaces as IllegalStateException naming the template.
public class MailTemplateServiceFailureTest {

    @Test
    public void render_writeFails_wrapsIoException() {
        MailTemplateService svc = new MailTemplateService() {
            @Override
            protected void write(PebbleTemplate compiled, Writer writer, Map<String, Object> vars) throws IOException {
                throw new IOException("disk gone");
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.render("action-email", Map.of()));
        assertTrue(e.getMessage().contains("action-email"));
        assertEquals("disk gone", e.getCause().getMessage());
    }
}
