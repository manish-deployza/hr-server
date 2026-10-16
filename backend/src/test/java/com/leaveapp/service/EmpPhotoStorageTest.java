package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.pojo.EmpPhotoPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;

// Profile photos with the media bucket switched on (against StubGcsServer) and off.
public class EmpPhotoStorageTest extends RepositoryTestSupport {

    /* Variables */

    // PNG signature + padding: enough for the magic-byte sniff
    private static final byte[] PNG = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };

    @Autowired
    private EmpService empSvc;

    @Autowired
    private EmpPhotoService photoSvc;

    @Autowired
    private MediaStorageService mediaSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private OrgService orgSvc;

    private StubGcsServer gcs;

    /* Public Methods */

    @BeforeEach
    public void setUp() throws Exception {
        gcs = new StubGcsServer();
        ReflectionTestUtils.setField(mediaSvc, "apiBase", gcs.baseUrl());
        ReflectionTestUtils.setField(mediaSvc, "tokenUrl", gcs.baseUrl() + "/token");
    }

    @AfterEach
    public void tearDown() {
        ReflectionTestUtils.setField(mediaSvc, "bucket", "");
        gcs.close();
    }

    @Test
    public void setPhoto_withBucketStoresObjectNotBytes() {
        ReflectionTestUtils.setField(mediaSvc, "bucket", "test-bucket");
        UserPojo user = anOrgAdmin("acme-media-1", "m1@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        EmpPhotoPojo photo = photoSvc.setPhoto(user.getId(), PNG, "image/png");

        assertNull(photo.getBytes());
        assertNotNull(photo.getStorageKey());
        assertTrue(photo.getStorageKey().startsWith("photos/" + user.getId() + "/"));
        assertTrue(photo.getStorageKey().endsWith(".png"));
        assertArrayEquals(PNG, photoSvc.readBytes(photo));
        assertEquals(1, gcs.objects.size());
    }

    @Test
    public void setPhoto_replacingDeletesTheOldObject() {
        ReflectionTestUtils.setField(mediaSvc, "bucket", "test-bucket");
        UserPojo user = anOrgAdmin("acme-media-2", "m2@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        String first = photoSvc.setPhoto(user.getId(), PNG, "image/png").getStorageKey();
        String second = photoSvc.setPhoto(user.getId(), PNG, "image/png").getStorageKey();

        assertTrue(!first.equals(second));
        assertEquals(1, gcs.objects.size());
        assertTrue(gcs.objects.containsKey(second));
    }

    @Test
    public void clearPhoto_removesTheObject() {
        ReflectionTestUtils.setField(mediaSvc, "bucket", "test-bucket");
        UserPojo user = anOrgAdmin("acme-media-3", "m3@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());
        photoSvc.setPhoto(user.getId(), PNG, "image/png");

        photoSvc.clearPhoto(user.getId());

        assertEquals(0, gcs.objects.size());
        assertNull(photoSvc.findOrNull(user.getId()));
    }

    @Test
    public void setPhoto_withoutBucketKeepsBytesInTheRow() {
        UserPojo user = anOrgAdmin("acme-media-4", "m4@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        EmpPhotoPojo photo = photoSvc.setPhoto(user.getId(), PNG, "image/png");

        assertNull(photo.getStorageKey());
        assertArrayEquals(PNG, photoSvc.readBytes(photo));
        assertEquals(0, gcs.objects.size());
    }

    /* Private Helper Methods */

    private UserPojo anOrgAdmin(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        return userSvc.createUser(org.getId(), email, "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.ORG_ADMIN);
    }
}
