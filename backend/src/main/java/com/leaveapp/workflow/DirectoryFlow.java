package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// DirectoryFlow -> UserService              -> UserRepo              -> DB (identity rows, org map)
// DirectoryFlow -> EmpService          -> EmpRepo          -> DB (profile rows, scope candidates)
// DirectoryFlow -> EmpSensitiveService -> EmpSensitiveRepo -> DB (P2-P4 sensitive-module rows)
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.data.AddressData;
import com.leaveapp.model.data.DirectoryData;
import com.leaveapp.model.data.EmergencyContactData;
import com.leaveapp.model.data.PersonalInfoData;
import com.leaveapp.model.enums.BloodGroup;
import com.leaveapp.model.enums.DirectoryScope;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.UserService;

// GET /api/directory (all scopes) -- read only. Users are created by the org admin
// (AdminUserController) and edited by HR/admin (HrEmpController); see
// prompts/features/employee-directory.md, including its "Sensitive Module (Steps 4/5/6/9)"
// section for the P2-P4 fields below.
@Service
public class DirectoryFlow {

    /* Variables */

    private static final String MASK_DOTS = "••••";

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private EmpSensitiveService sensSvc;

    /* Public Methods */

    // Candidates by scope: ORG/REPORTS are DB-filtered, DB-sorted, and DB-paginated
    // (EmpService.searchDirectory's join already excludes cross-org/exited rows) --
    // this never loads more than the one requested page into memory. SELF is always
    // exactly the caller's own row, so it's filtered in Java instead (nothing to
    // paginate). includeSensitive gates the Steps 4/5/6/9 fields -- true only for an
    // ORG_ADMIN or HR caller (DirectoryController.list computes it), never derived from
    // scope.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Page<DirectoryData> list(Long orgId, DirectoryScope scope, Long viewerId,
        String department, Long managerId, String search, Pageable pg, boolean includeSensitive) {
        String dept = hasText(department) ? department.trim() : null;
        String q = hasText(search) ? search.trim() : null;

        Page<EmpPojo> empPage = switch (scope) {
            case ORG -> empSvc.searchDirectory(orgId, managerId, dept, q, sorted(pg));
            case REPORTS -> empSvc.searchDirectory(orgId, viewerId, dept, q, sorted(pg));
            case SELF -> selfPage(orgId, viewerId, dept, q, pg);
        };

        List<Long> ids = empPage.getContent().stream().map(EmpPojo::getUserId).toList();
        Map<Long, UserPojo> users = userSvc.findByIds(ids);
        List<DirectoryData> rows = empPage.getContent().stream()
            .map(emp -> toData(users.get(emp.getUserId()), emp, includeSensitive))
            .toList();

        // Batch-fetched for just this page's ids (<= page size, <= 200), same as
        // `users` above. Rows created before this feature shipped have no
        // EmpSensitivePojo yet; those fields simply stay null, not an error. phone is
        // fetched unconditionally (it moved off `employee`, but stays visible at the
        // same level it always was, independent of includeSensitive); the rest of the
        // sensitive module (Steps 4/5/6/9) is still ORG_ADMIN-only.
        if (!rows.isEmpty()) {
            Map<Long, EmpSensitivePojo> sensById = sensSvc.findByUserIds(ids);
            for (DirectoryData d : rows) {
                EmpSensitivePojo sens = sensById.get(d.getId());
                if (sens == null) {
                    continue;
                }
                d.setPhone(sens.getPhone());
                if (includeSensitive) {
                    mergeSensitive(d, sens);
                }
            }
        }
        return new PageImpl<>(rows, pg, empPage.getTotalElements());
    }

    /* Private Helper Methods */

    // Fixed sort every list() caller gets, regardless of scope -- name (blank/null
    // last) then id, expressed as a Sort so EmpRepo.searchDirectory's ORDER BY runs in
    // the database instead of a Java Comparator over an already-loaded list. EmpPojo's
    // own id field is `userId`, not `id` -- see EmpPojo.java.
    private static Pageable sorted(Pageable pg) {
        return PageRequest.of(pg.getPageNumber(), pg.getPageSize(),
            Sort.by(Sort.Order.by("name").nullsLast(), Sort.Order.by("userId")));
    }

    // SELF is always exactly the caller's own row -- filtered the same way ORG/REPORTS
    // are (a department/search filter can legitimately make this scope return zero
    // rows too), but there's only ever one candidate, so it's cheaper to check it in
    // Java than to round-trip a Pageable query for a single row.
    private Page<EmpPojo> selfPage(Long orgId, Long viewerId, String department, String search, Pageable pg) {
        EmpPojo self = empSvc.findByUserId(viewerId);
        UserPojo user = userSvc.findById(viewerId);
        boolean matches = orgId.equals(user.getOrgId())
            && EmpStatus.EXITED != self.getStatus()
            && (department == null || department.equalsIgnoreCase(self.getDepartment()))
            && (search == null
                || (self.getName() != null && self.getName().toLowerCase().contains(search.toLowerCase())));
        List<EmpPojo> content = matches && pg.getOffset() == 0 ? List.of(self) : List.of();
        return new PageImpl<>(content, pg, matches ? 1 : 0);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    // Last 4 characters only, even for an ORG_ADMIN -- there is no reveal endpoint in
    // this build (see the feature doc). This is the actual masking control, not the
    // frontend's client-side maskTail; the fields are stored plain, so this is the only
    // place the full value is ever withheld.
    private static String maskTail(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 4) {
            return MASK_DOTS;
        }
        return MASK_DOTS + " " + value.substring(value.length() - 4);
    }

    private static void mergeSensitive(DirectoryData data, EmpSensitivePojo sens) {
        PersonalInfoData personalInfo = new PersonalInfoData();
        personalInfo.setDateOfBirth(sens.getDateOfBirth());
        personalInfo.setGender(sens.getGender());
        personalInfo.setNationality(sens.getNationality());
        personalInfo.setMaritalStatus(sens.getMaritalStatus());
        personalInfo.setBloodGroup(BloodGroup.labelOf(sens.getBloodGroup()));
        personalInfo.setPersonalEmail(sens.getPersonalEmail());
        data.setPersonalInfo(personalInfo);

        data.setAddress(AddressData.from(sens.getAddress()));

        EmergencyContactData emergency = new EmergencyContactData();
        emergency.setEmergencyName(sens.getEmergencyName());
        emergency.setEmergencyRelationship(sens.getEmergencyRelationship());
        emergency.setEmergencyPhone(sens.getEmergencyPhone());
        data.setEmergencyContact(emergency);

        data.setBankAccount(maskTail(sens.getBankAccount()));
        data.setBankIfsc(sens.getBankIfsc());
        data.setNationalId(maskTail(sens.getNationalId()));
        data.setTaxId(maskTail(sens.getTaxId()));
        data.setPassport(maskTail(sens.getPassport()));
    }

    private static DirectoryData toData(UserPojo user, EmpPojo emp, boolean includeSensitive) {
        DirectoryData data = new DirectoryData();
        data.setId(user.getId());
        data.setEmployeeCode(emp.getEmployeeCode());
        data.setName(emp.getName());
        data.setEmail(user.getEmail());
        data.setDesignation(emp.getDesignation());
        data.setDepartment(emp.getDepartment());
        data.setEmployeeType(emp.getEmployeeType());
        data.setStatus(HrEmpMapper.statusOf(user, emp));
        data.setDateOfJoining(emp.getDateOfJoining());
        data.setManagerId(emp.getManagerId());
        data.setWorkLocation(emp.getWorkLocation());
        // basicSalary is P3 (see directory-scope.js's DIRECTORY_SENSITIVE_FIELDS) --
        // gated the same as the Steps 4/5/6/9 module, not returned to HR/managers/self.
        if (includeSensitive) {
            data.setBasicSalary(emp.getBasicSalary());
        }
        return data;
    }
}
