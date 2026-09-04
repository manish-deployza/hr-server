
package com.leaveapp.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.SessionData;
import com.leaveapp.model.form.ProfileForm;
import com.leaveapp.workflow.EmployeeFlow;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(EmployeeController.class);

    @Autowired
    private EmployeeFlow empFlow;

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
}
