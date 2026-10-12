package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// LeaveTypeService -> LeaveTypeRepo -> DB (leave-type catalog rows)
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.repo.LeaveTypeRepo;

// Leave-type catalog: reads, and HR create/update/deactivate, all scoped to one
// org -- every leave type belongs to exactly one organization. Data concerns only
// (code uniqueness, field validation); which types a given employee may see is the
// Flow's call.
@Service
public class LeaveTypeService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(LeaveTypeService.class);

    @Autowired
    private LeaveTypeRepo typeRepo;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveTypePojo> listActive(Long orgId) {
        return typeRepo.findByOrgIdAndActiveTrue(orgId);
    }

    // Includes deactivated types -- used to resolve the code/name of a type on an
    // existing request even after HR has retired it.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveTypePojo> listAll(Long orgId) {
        return typeRepo.findByOrgId(orgId);
    }

    // Active types with no applicable_employee_type restriction (null) or the caller's own type.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveTypePojo> listApplicable(Long orgId, String employeeType) {
        return listActive(orgId).stream()
            .filter(t -> t.getApplicableType() == null || t.getApplicableType().equalsIgnoreCase(employeeType))
            .toList();
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeaveTypePojo findById(Long orgId, Long id) {
        return typeRepo.findByIdAndOrgId(id, orgId) //
            .orElseThrow(() -> new NotFoundException("leave type not found id=" + id));
    }

    // Used by the apply path -- an unknown or inactive type is a 400, not a 404.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeaveTypePojo findActive(Long orgId, Long id) {
        LeaveTypePojo type = typeRepo.findByIdAndOrgId(id, orgId) //
            .orElseThrow(() -> new BadRequestException("unknown leave type"));
        if (!type.isActive()) {
            throw new BadRequestException("this leave type is not active");
        }
        return type;
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaveTypePojo create(Long orgId, String name, String infoNote,
        Integer noticeDays, BigDecimal daysPerYear) {
        require(name, "name");
        String validCode = uniqueCodeFrom(orgId, name);
        Instant now = Instant.now();
        LeaveTypePojo type = new LeaveTypePojo();
        type.setOrgId(orgId);
        type.setCode(validCode);
        type.setAccrualStrategy(AccrualStrategy.ANNUAL_PRORATA);
        type.setApproverRole(ApproverRole.MANAGER);
        type.setActive(true);
        type.setCreatedAt(now);
        type.setUpdatedAt(now);
        applyFields(type, name, infoNote, noticeDays, daysPerYear);
        LeaveTypePojo saved = typeRepo.save(type);
        log.info("leave type created id={} orgId={} code={}", saved.getId(), orgId, validCode);
        return saved;
    }

    // PATCH: null field = unchanged.
    @Transactional(rollbackFor = Exception.class)
    public LeaveTypePojo update(Long orgId, Long id, String code, String name, String infoNote,
        Integer noticeDays, BigDecimal daysPerYear) {
        LeaveTypePojo type = findById(orgId, id);
        if (code != null && !code.trim().isEmpty() && !code.trim().equals(type.getCode())) {
            if (typeRepo.findByOrgIdAndCode(orgId, code.trim()).isPresent()) {
                throw new ConflictException("a leave type with this code already exists");
            }
            type.setCode(code.trim());
        }
        applyFields(type, name, infoNote, noticeDays, daysPerYear);
        return type;
    }

    // Soft-delete: leave history keeps its FK, so the row stays and just drops out
    // of every active-types listing.
    @Transactional(rollbackFor = Exception.class)
    public void deactivate(Long orgId, Long id) {
        findById(orgId, id).setActive(false);
        log.info("leave type deactivated id={} orgId={}", id, orgId);
    }

    // "14 days/year" for the apply-form label; "" when the type has no annual entitlement.
    public static String daysPerYearLabel(LeaveTypePojo type) {
        if (type.getDaysPerYear() == null) {
            return "";
        }
        return type.getDaysPerYear().stripTrailingZeros().toPlainString() + " days/year";
    }

    /* Private Helper Methods */

    private void applyFields(LeaveTypePojo type, String name, String infoNote,
        Integer noticeDays, BigDecimal daysPerYear) {
        if (name != null) {
            type.setName(name.trim());
        }
        if (infoNote != null) {
            type.setInfoNote(infoNote);
        }
        if (noticeDays != null) {
            type.setNoticeDays(noticeDays);
        }
        if (daysPerYear != null) {
            type.setDaysPerYear(daysPerYear);
        }
    }

    // code is no longer client-supplied on create -- derive it from the name
    // ("Sick Leave" -> "SICK_LEAVE") and disambiguate on collision (_2, _3, ...).
    private String uniqueCodeFrom(Long orgId, String name) {
        String base = name.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (base.isEmpty()) {
            base = "LEAVE_TYPE";
        }
        base = base.substring(0, Math.min(base.length(), 64));
        String code = base;
        int suffix = 2;
        while (typeRepo.findByOrgIdAndCode(orgId, code).isPresent()) {
            String suffixStr = "_" + suffix++;
            code = base.substring(0, Math.min(base.length(), 64 - suffixStr.length())) + suffixStr;
        }
        return code;
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(field + " is required");
        }
        return value.trim();
    }
}
