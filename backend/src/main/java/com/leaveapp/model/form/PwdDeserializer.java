package com.leaveapp.model.form;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Reads a JSON string password field directly into a byte[] -- the security baseline
 * forbids a raw password ever sitting in a String field. Jackson still materializes a
 * transient String internally to parse the JSON token (unavoidable -- JSON text is
 * String-based); this deserializer just stops the Form from holding one long-term.
 */
public class PwdDeserializer extends JsonDeserializer<byte[]> {

    /* Public Methods */

    @Override
    public byte[] deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String s = p.getValueAsString();
        return s != null ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }
}
