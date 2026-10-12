
package com.leaveapp.rest;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.PhotoData;
import com.leaveapp.model.data.SessionData;
import com.leaveapp.model.form.ProfileForm;
import com.leaveapp.workflow.EmpFlow;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/employees")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR', 'EMPLOYEE')")
public class EmpController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(EmpController.class);

    @Autowired
    private EmpFlow empFlow;

    /* Public Methods */

    @GetMapping("/me")
    public SessionData me() {
        Long userId = requireUserId();
        log.info("GET /api/employees/me userId={}", userId);
        return empFlow.getProfile(userId);
    }

    @PatchMapping("/me")
    public SessionData updateMe(@RequestBody ProfileForm form) {
        Long userId = requireUserId();
        log.info("PATCH /api/employees/me userId={}", userId);
        return empFlow.updateProfile(userId, form);
    }

    @PutMapping("/me/photo")
    public SessionData uploadPhoto(@RequestParam("photo") MultipartFile photo) {
        Long userId = requireUserId();
        log.info("PUT /api/employees/me/photo userId={}", userId);
        try {
            return empFlow.uploadPhoto(userId, photo.getBytes(), photo.getContentType());
        } catch (IOException e) {
            throw new BadRequestException("could not read uploaded photo");
        }
    }

    @GetMapping("/me/photo")
    public void getPhoto(HttpServletResponse res) throws IOException {
        Long userId = requireUserId();
        PhotoData photo = empFlow.getPhoto(userId);
        res.setContentType(photo.getContentType());
        res.setContentLength(photo.getBytes().length);
        res.getOutputStream().write(photo.getBytes());
    }

    @DeleteMapping("/me/photo")
    public SessionData deletePhoto() {
        Long userId = requireUserId();
        log.info("DELETE /api/employees/me/photo userId={}", userId);
        return empFlow.deletePhoto(userId);
    }
}
