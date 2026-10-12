package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// HrEmpFlow -> UserService     -> UserRepo     -> DB (identity row: email, role, status, password hash)
// HrEmpFlow -> EmpService -> EmpRepo -> DB (profile row: code, name, manager, salary)
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.model.data.EmpRowData;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.InviteMailer;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// HR/ORG admin manages employee accounts in their own org: a `user` row + a 1:1
// `employee` row, always as a unit. Holds NO authorization logic -- the controller
// owns every access decision (role gate, org scope, per-target role check) before
// calling in; this Flow only re-validates the role *value* it is about to write.
@Service
public class HrEmpFlow {

    /* Variables */

    private static final Set<UserRole> WRITABLE_ROLES = EnumSet.of(UserRole.EMPLOYEE, UserRole.HR);
    private static final Set<EmpStatus> STATUSES = EnumSet.of(EmpStatus.ACTIVE, EmpStatus.ON_NOTICE, EmpStatus.EXITED);
    private static final String DEFAULT_EMP_TYPE = "REGULAR";

    @Autowired
    private UserService userSvc;
    @Autowired
    private EmpService empSvc;
    @Autowired
    private EmpSensitiveService sensSvc;
    @Autowired
    private InviteMailer inviteMailer;
    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    // All employees in the caller's org. Two queries (users + profiles), zipped in
    // memory by id -- no per-row org/manager lookups; the HR screen derives manager
    // names and the "Manager" badge client-side from the whole list.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<EmpRowData> list(Long orgId) {
        Map<Long, UserPojo> users = userSvc.findByOrg(orgId).stream()
            .collect(Collectors.toMap(UserPojo::getId, Function.identity()));
        List<EmpPojo> emps = empSvc.findByOrg(orgId);
        Map<Long, EmpSensitivePojo> sensById = sensSvc.findByUserIds(
            emps.stream().map(EmpPojo::getUserId).collect(Collectors.toList()));
        List<EmpRowData> rows = new ArrayList<>();
        for (EmpPojo emp : emps) {
            UserPojo user = users.get(emp.getUserId());
            if (user != null) {
                rows.add(HrEmpMapper.toRow(user, emp, sensById.get(emp.getUserId())));
            }
        }
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long orgId, EmpForm form) {
        subscriptionSvc.assertSeatAvailable(orgId);
        String email = FieldRules.email("email", form.getEmail());
        if (email == null || email.isBlank()) {
            throw new BadRequestException("email is required");
        }
        requireWritableRole(form.getRole());
        if (userSvc.existsInOrgByEmail(orgId, email)) {
            throw new ConflictException("an account with this email already exists");
        }
        if (form.getManagerId() != null && !userSvc.existsInOrg(orgId, form.getManagerId())) {
            throw new BadRequestException("managerId does not resolve to a user in this organization");
        }
        form.setEmail(email);
        return persistRow(orgId, form);
    }

    // Org admin's slim create: name + email + role only, status INVITED, invite mail
    // queued by persistRow. Everything else is the user's (onboarding) or HR's (edit).
    @Transactional(rollbackFor = Exception.class)
    public Long invite(Long orgId, UserInviteForm form) {
        String name = FieldRules.text("name", form.getName(), 128);
        if (name == null || name.isEmpty()) {
            throw new BadRequestException("name is required");
        }
        EmpForm empForm = new EmpForm();
        empForm.setName(name);
        empForm.setEmail(form.getEmail());
        empForm.setRole(form.getRole());
        return create(orgId, empForm);
    }

    // New link for a user who hasn't set a password yet -- only INVITED qualifies; anyone
    // further along has no use for (and must not be reset by) an invite link.
    @Transactional(rollbackFor = Exception.class)
    public void resendInvite(Long targetId) {
        UserPojo user = userSvc.findById(targetId);
        if (UserStatus.INVITED != user.getStatus()) {
            throw new BadRequestException("only invited users can be re-invited");
        }
        inviteMailer.send(user.getId(), user.getEmail());
    }

    // PATCH one employee. Every field is optional; a null (or, for strings the
    // frontend blanks, an empty) value means "leave unchanged". No password field --
    // passwords are only ever set by the user, from the invite or forgot-password mail.
    // orgId/targetId are already authorized by the controller (org match, and that
    // this caller may touch this target's role); the payload-role checks are also
    // the controller's.
    @Transactional(rollbackFor = Exception.class)
    public EmpRowData update(Long orgId, Long targetId, EmpForm form) {
        UserPojo user = userSvc.findById(targetId);
        EmpPojo emp = empSvc.findByUserId(targetId);
        if (hasText(form.getEmail())) {
            String newEmail = form.getEmail().trim();
            if (!newEmail.equals(user.getEmail())) {
                if (userSvc.existsInOrgByEmail(orgId, newEmail)) {
                    throw new ConflictException("an account with this email already exists");
                }
                user.setEmail(newEmail);
            }
        }
        if (form.getRole() != null) {
            UserRole newRole = requireWritableRole(form.getRole());
            if (newRole != user.getRole()) {
                user.setTokensValidAfter(Instant.now()); // role changed -- any existing token is stale
            }
            user.setRole(newRole);
        }
        if (form.getStatus() != null) {
            EmpStatus next = parseStatus(form.getStatus());
            // an onboarding / onboarded employee can only leave the onboarding lifecycle
            // by finishing it (login makes them ACTIVE) -- HR may exit them, never skip it
            boolean onboarding = EmpStatus.ONBOARDING == emp.getStatus() || EmpStatus.ONBOARDED == emp.getStatus();
            if (onboarding && EmpStatus.EXITED != next) {
                throw new BadRequestException("status cannot be changed until the user finishes onboarding");
            }
            emp.setStatus(next);
            // exiting disables the account; un-exiting re-enables it
            if (EmpStatus.EXITED == next) {
                user.setStatus(UserStatus.DISABLED);
            } else if (UserStatus.DISABLED == user.getStatus()) {
                user.setStatus(UserStatus.ACTIVE);
            }
        }
        if (form.getManagerId() != null) {
            if (form.getManagerId().equals(targetId)) {
                throw new BadRequestException("an employee cannot be their own manager");
            }
            if (!userSvc.existsInOrg(orgId, form.getManagerId())) {
                throw new BadRequestException("managerId does not resolve to a user in this organization");
            }
            emp.setManagerId(form.getManagerId());
        }
        empSvc.applyAdminFields(emp, form.getEmployeeCode(), form.getName(),
            form.getDesignation(), form.getDepartment(), form.getEmployeeType(),
            form.getDateOfJoining(), form.getDateOfExit(), form.getBasicSalary());
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(targetId);
        if (sens == null) {
            sens = sensSvc.createEmpty(targetId);
        }
        sensSvc.applyContactFields(sens, form.getPhone(), null);
        // all managed entities -- dirty fields flush on commit (@PreUpdate bumps updated_at).
        return HrEmpMapper.toRow(user, emp, sens);
    }

    // Soft-delete one employee: employee status=EXITED, date_of_exit=today, account
    // DISABLED. No hard delete -- leave history keeps its FKs. Idempotent: an
    // already-EXITED employee is left untouched (its original exit date stays).
    // targetId is already authorized by the controller.
    @Transactional(rollbackFor = Exception.class)
    public void deactivate(Long targetId) {
        EmpPojo emp = empSvc.findByUserId(targetId);
        if (EmpStatus.EXITED == emp.getStatus()) {
            return;
        }
        emp.setStatus(EmpStatus.EXITED);
        emp.setDateOfExit(LocalDate.now());
        UserPojo user = userSvc.findById(targetId);
        user.setStatus(UserStatus.DISABLED);
        user.setTokensValidAfter(Instant.now());
        // managed entities flush on commit.
    }

    /* Private Helper Methods */

    // Insert half of create -- package-private so EmpImportFlow's phase 2 can
    // reuse it once a row has already passed HrImportSupport.resolveRow. No
    // validation here: every caller must validate before calling this.
    @Transactional(rollbackFor = Exception.class)
    Long persistRow(Long orgId, EmpForm form) {
        // createUser sets all lifecycle columns; the user starts INVITED and can't log in
        // until they set a password from the invite mail (admin invite and bulk import alike).
        UserPojo user = userSvc.createUser(orgId, form.getEmail(), new byte[0], form.getRole(), UserStatus.INVITED);
        EmpPojo emp = new EmpPojo();
        emp.setUserId(user.getId());
        emp.setOrgId(orgId);
        // code and joining date stay null until HR knows them -- no placeholders
        emp.setEmployeeCode(hasText(form.getEmployeeCode()) ? form.getEmployeeCode().trim() : null);
        emp.setName(form.getName());
        emp.setDateOfJoining(form.getDateOfJoining());
        emp.setDateOfExit(form.getDateOfExit());
        emp.setStatus(EmpStatus.ONBOARDING);
        emp.setEmployeeType(hasText(form.getEmployeeType()) ? form.getEmployeeType().trim() : DEFAULT_EMP_TYPE);
        emp.setDesignation(form.getDesignation());
        emp.setDepartment(form.getDepartment());
        emp.setManagerId(form.getManagerId());
        emp.setBasicSalary(form.getBasicSalary());
        empSvc.createProfile(emp);
        // every employee gets a sensitive row from creation on -- this is the one shared
        // insert path (see class comment), so it's also the one place phone (PII) is set.
        EmpSensitivePojo sens = sensSvc.createEmpty(user.getId());
        sensSvc.applyContactFields(sens, form.getPhone(), null);
        inviteMailer.send(user.getId(), user.getEmail());
        return user.getId();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static UserRole requireWritableRole(UserRole role) {
        if (!WRITABLE_ROLES.contains(role)) {
            throw new BadRequestException("role must be EMPLOYEE or HR");
        }
        return role;
    }

    private static EmpStatus parseStatus(String value) {
        try {
            EmpStatus status = EmpStatus.valueOf(value);
            if (!STATUSES.contains(status)) {
                throw new BadRequestException("status must be ACTIVE, ON_NOTICE, or EXITED");
            }
            return status;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("status must be ACTIVE, ON_NOTICE, or EXITED");
        }
    }
}
