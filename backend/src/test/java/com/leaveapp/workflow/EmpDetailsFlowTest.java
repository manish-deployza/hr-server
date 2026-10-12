package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.AuditAction;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.AddressForm;
import com.leaveapp.model.form.EmpDetailsForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.AuditLogPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.repo.AuditLogRepo;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;

public class EmpDetailsFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private EmpDetailsFlow flow;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private AuditLogRepo auditRepo;

    /* Public Methods */

    @Test
    public void update_patchesOnlySentFields_andAuditsSectionNamesNotValues() {
        Long targetId = anEmployee("acme-det-1", "e1@acme.test");
        PayIdsForm pay = new PayIdsForm();
        pay.setBankAccount("9988776655");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setPhone("+91 90000 22222");
        form.setPayIds(pay);

        flow.update(7L, targetId, form);

        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(targetId);
        assertEquals("+91 90000 22222", sens.getPhone());
        assertEquals("9988776655", sens.getBankAccount());
        assertNull(sens.getBankIfsc()); // not sent -> unchanged

        AuditLogPojo audit = auditRepo.findAll().stream()
            .filter(a -> a.getEntityId().equals(targetId)).findFirst().orElseThrow();
        assertEquals(AuditAction.EMPLOYEE_DETAILS_UPDATED, audit.getAction());
        assertEquals(7L, audit.getPerformedBy());
        assertTrue(audit.getAfterJson().contains("payIds"));
        assertFalse(audit.getAfterJson().contains("9988776655")); // never the value
    }

    @Test
    public void update_rejectsInvalidCountryCode() {
        Long targetId = anEmployee("acme-det-2", "e2@acme.test");
        AddressForm address = new AddressForm();
        address.setCountry("ZZ");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setAddress(address);
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, form));
    }

    /* Private Helper Methods */

    private Long anEmployee(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        UserInviteForm invite = new UserInviteForm();
        invite.setName("Eve Employee");
        invite.setEmail(email);
        invite.setRole(UserRole.EMPLOYEE);
        return hrEmpFlow.invite(org.getId(), invite);
    }
}
