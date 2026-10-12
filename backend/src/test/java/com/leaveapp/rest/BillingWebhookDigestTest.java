package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

// digestHex: known SHA-256 vector, and the unknown-algorithm failure path.
public class BillingWebhookDigestTest {

    private final BillingWebhookController controller = new BillingWebhookController();

    @Test
    public void digestHex_sha256_matchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            controller.digestHex("SHA-256", "abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void digestHex_unknownAlgorithm_throwsIllegalState() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> controller.digestHex("NOPE-1", new byte[0]));
        assertEquals("NOPE-1 not available", e.getMessage());
    }
}
