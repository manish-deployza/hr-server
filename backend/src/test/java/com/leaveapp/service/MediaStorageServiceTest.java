package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;

public class MediaStorageServiceTest {

    /* Variables */

    private StubGcsServer gcs;
    private MediaStorageService svc;

    /* Public Methods */

    @BeforeEach
    public void setUp() throws Exception {
        gcs = new StubGcsServer();
        svc = new MediaStorageService();
        ReflectionTestUtils.setField(svc, "bucket", "test-bucket");
        ReflectionTestUtils.setField(svc, "apiBase", gcs.baseUrl());
        ReflectionTestUtils.setField(svc, "tokenUrl", gcs.baseUrl() + "/token");
    }

    @AfterEach
    public void tearDown() {
        gcs.close();
    }

    @Test
    public void isEnabled_falseWhenBucketBlank() {
        ReflectionTestUtils.setField(svc, "bucket", "");
        assertFalse(svc.isEnabled());
    }

    @Test
    public void putGetDelete_roundTripsBytesUnderASlashedKey() {
        byte[] bytes = { 1, 2, 3, 4 };
        svc.put("photos/7/a b.png", bytes, "image/png");

        assertTrue(svc.isEnabled());
        assertArrayEquals(bytes, svc.get("photos/7/a b.png"));

        svc.delete("photos/7/a b.png");
        assertThrows(BadRequestException.class, () -> svc.get("photos/7/a b.png"));
    }

    @Test
    public void token_isCachedAcrossCalls() {
        svc.put("k1", new byte[] { 1 }, "image/png");
        svc.get("k1");
        svc.delete("k1");
        assertEquals(1, gcs.tokenCalls.get());
    }

    @Test
    public void delete_ofMissingObjectDoesNotThrow() {
        svc.delete("photos/none.png");
    }

    @Test
    public void put_failsWhenStorageRejects() {
        ReflectionTestUtils.setField(svc, "bucket", "other-bucket"); // stub only serves test-bucket
        assertThrows(IllegalStateException.class, () -> svc.put("k", new byte[] { 1 }, "image/png"));
    }
}
