package com.leaveapp.service;

// import flow (layering: Service -> Repo | Service):
// EmpPhotoService -> EmpPhotoRepo -> DB (employee_photo rows)
// EmpPhotoService -> EmpService   -> DB (the employee must exist)
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.pojo.EmpPhotoPojo;
import com.leaveapp.repo.EmpPhotoRepo;

// The self-service profile photo, split out of EmpService along with its table (see
// EmpPhotoPojo). A row exists only while a photo is set.
@Service
public class EmpPhotoService {

    /* Variables */

    // Magic-byte signatures for the three allowed photo formats -- see
    // prompts/features/employee-auth-and-profile.changes.md's Validation section for why
    // this, not a full ImageIO decode, is the check.
    private static final byte[] SIG_JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] SIG_PNG = { (byte) 0x89, 0x50, 0x4E, 0x47 };
    private static final int WEBP_SIG_LEN = 12;

    @Value("${app.photo.max-bytes}")
    private int maxPhotoBytes;

    @Autowired
    private EmpPhotoRepo photoRepo;

    @Autowired
    private EmpService empSvc;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public EmpPhotoPojo findOrNull(Long userId) {
        return photoRepo.findById(userId).orElse(null);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public boolean hasPhoto(Long userId) {
        return photoRepo.existsByUserId(userId);
    }

    // Validates size/format then stores the photo. contentType is the client-declared
    // multipart type; verified against the actual bytes below rather than trusted as-is.
    @Transactional(rollbackFor = Exception.class)
    public EmpPhotoPojo setPhoto(Long userId, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("photo is empty");
        }
        if (bytes.length > maxPhotoBytes) {
            throw new BadRequestException("photo exceeds " + maxPhotoBytes + " bytes");
        }
        String type = sniffType(bytes);
        if (type == null || !type.equals(contentType)) {
            throw new BadRequestException("photo must be JPEG, PNG, or WebP");
        }
        empSvc.findByUserId(userId); // 404 for an unknown employee
        Instant now = Instant.now();
        EmpPhotoPojo photo = findOrNull(userId);
        if (photo == null) {
            photo = new EmpPhotoPojo();
            photo.setUserId(userId);
            photo.setCreatedAt(now);
        }
        photo.setBytes(bytes);
        photo.setContentType(type);
        photo.setUpdatedAt(now);
        return photoRepo.save(photo);
    }

    // Idempotent -- clearing when no photo is set is a no-op.
    @Transactional(rollbackFor = Exception.class)
    public void clearPhoto(Long userId) {
        photoRepo.findById(userId).ifPresent(photoRepo::delete);
    }

    /* Private Helper Methods */

    // Magic-byte sniff, not a full image decode -- see the Validation section of
    // prompts/features/employee-auth-and-profile.changes.md for why.
    private static String sniffType(byte[] b) {
        if (startsWith(b, SIG_JPEG)) {
            return "image/jpeg";
        }
        if (startsWith(b, SIG_PNG)) {
            return "image/png";
        }
        // RIFF <4-byte size> WEBP
        if (b.length >= WEBP_SIG_LEN //
            && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' //
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] b, byte[] sig) {
        if (b.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (b[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }
}
