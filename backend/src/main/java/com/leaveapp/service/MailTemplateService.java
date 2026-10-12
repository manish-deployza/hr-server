package com.leaveapp.service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import io.pebbletemplates.pebble.template.PebbleTemplate;

// Renders the HTML body for the *Mailer services from classpath templates instead of
// Java text blocks -- keeps markup out of .java files and gets auto-escaping on every
// substituted value for free. Builds its own PebbleEngine (config classes declare no
// beans -- see AppConfig), same pattern as MailService building its own JavaMailSender.
@Service
public class MailTemplateService {

    /* Variables */

    private final PebbleEngine engine;

    /* Constructors */

    public MailTemplateService() {
        ClasspathLoader loader = new ClasspathLoader();
        loader.setPrefix("templates/mail");
        loader.setSuffix(".peb");
        engine = new PebbleEngine.Builder().loader(loader).build();
    }

    /* Public Methods */

    public String render(String template, Map<String, Object> vars) {
        try {
            PebbleTemplate compiled = engine.getTemplate(template);
            Writer writer = new StringWriter();
            write(compiled, writer, vars);
            return writer.toString();
        } catch (IOException e) {
            throw new IllegalStateException("failed to render mail template " + template, e);
        }
    }

    /* Protected Methods */

    // Pebble's evaluate declares IOException; split out so a test can trigger it.
    protected void write(PebbleTemplate compiled, Writer writer, Map<String, Object> vars) throws IOException {
        compiled.evaluate(writer, vars);
    }
}
