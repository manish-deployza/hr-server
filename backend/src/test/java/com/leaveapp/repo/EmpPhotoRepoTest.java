package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.EmpPhotoPojo;

// employee_photo: 1:1 with employee/user, PK = user_id. A row exists only while a photo
// is set, so bytes and content type are both required.
public class EmpPhotoRepoTest extends RepositoryTestSupport {

    /* Variables */

    private static final AtomicLong UID = new AtomicLong(3000);

    @Autowired
    private EmpPhotoRepo photoRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        EmpPhotoPojo saved = photoRepo.saveAndFlush(EmpPhotoTestData.aPhoto(UID.incrementAndGet()));
        assertNotNull(saved.getUserId());
    }

    @Test
    public void existsByUserId_trueOnlyForStoredPhoto() {
        Long userId = UID.incrementAndGet();
        photoRepo.saveAndFlush(EmpPhotoTestData.aPhoto(userId));

        assertTrue(photoRepo.existsByUserId(userId));
        assertFalse(photoRepo.existsByUserId(UID.incrementAndGet()));
    }

    @Test
    public void save_nullBytesWithStorageKey_persists() {
        // bytes are null once the photo lives in the media bucket (storage_key)
        EmpPhotoPojo p = EmpPhotoTestData.aPhoto(UID.incrementAndGet());
        p.setBytes(null);
        p.setStorageKey("photos/1/a.png");
        EmpPhotoPojo saved = photoRepo.saveAndFlush(p);
        assertEquals("photos/1/a.png", photoRepo.findById(saved.getUserId()).orElseThrow().getStorageKey());
    }

    @Test
    public void save_nullContentType_throws() {
        EmpPhotoPojo p = EmpPhotoTestData.aPhoto(UID.incrementAndGet());
        p.setContentType(null);
        assertThrows(DataIntegrityViolationException.class, () -> photoRepo.saveAndFlush(p));
    }
}
