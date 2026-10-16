package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// EmpFlow -> UserService     -> UserRepo     -> DB (identity: email, role, status)
// EmpFlow -> EmpService -> EmpRepo -> DB (profile read/update, manager lookup)
// EmpFlow -> OrgService -> OrgRepo -> DB (org display name)
// EmpFlow -> EmpPhotoService -> EmpPhotoRepo -> DB (profile photo)
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.AddressData;
import com.leaveapp.model.data.PhotoData;
import com.leaveapp.model.data.SessionData;
import com.leaveapp.model.form.ProfileForm;
import com.leaveapp.pojo.EmpPhotoPojo;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.EmpPhotoService;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.UserService;

@Service
public class EmpFlow {

    /* Variables */

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private EmpPhotoService photoSvc;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public SessionData getProfile(Long userId) {
        return toData(userSvc.findById(userId), empSvc.findByUserId(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionData updateProfile(Long userId, ProfileForm form) {
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(userId);
        if (sens == null) {
            sens = sensSvc.createEmpty(userId);
        }
        sensSvc.applyContactFields(sens, form.getPhone(), form.getAddress());
        return toData(userSvc.findById(userId), empSvc.findByUserId(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionData uploadPhoto(Long userId, byte[] bytes, String contentType) {
        photoSvc.setPhoto(userId, bytes, contentType);
        return toData(userSvc.findById(userId), empSvc.findByUserId(userId));
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PhotoData getPhoto(Long userId) {
        empSvc.findByUserId(userId); // 404 for an unknown employee, same as before the split
        EmpPhotoPojo photo = photoSvc.findOrNull(userId);
        if (photo == null) {
            throw new NotFoundException("no photo set for userId=" + userId);
        }
        PhotoData data = new PhotoData();
        data.setBytes(photoSvc.readBytes(photo));
        data.setContentType(photo.getContentType());
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionData deletePhoto(Long userId) {
        empSvc.findByUserId(userId);
        photoSvc.clearPhoto(userId);
        return toData(userSvc.findById(userId), empSvc.findByUserId(userId));
    }

    /* Private Helper Methods */

    // (UserPojo + EmpPojo) -> SessionData (API shape) -- never return a *Pojo
    // past this Flow. This is the seam where a role would pick which detail table to
    // read; today ORG_ADMIN/HR/EMPLOYEE all map to `employee`, so there is no
    // switch yet -- see prompts/schema.md.
    private SessionData toData(UserPojo user, EmpPojo emp) {
        SessionData data = new SessionData();
        data.setId(user.getId());
        data.setEmail(user.getEmail());
        data.setEmailVerified(user.isEmailVerified()); // verified-or-not lives on `user`, not `employee`
        data.setRole(user.getRole().name());
        data.setStatus(HrEmpMapper.statusOf(user, emp));

        data.setEmployeeCode(emp.getEmployeeCode());
        data.setName(emp.getName());
        data.setHasPhoto(photoSvc.hasPhoto(user.getId()));

        // phone/address (PII) plus the read-only structured address from the sensitive
        // module, if HR has ever filled it in -- see SessionData's comment. No sensitive
        // row yet -> leave these null.
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(user.getId());
        if (sens != null) {
            data.setPhone(sens.getPhone());
            data.setAddress(sens.getContactAddress());
            data.setStructuredAddress(AddressData.from(sens.getAddress()));
        }
        data.setManagerId(emp.getManagerId());
        data.setDepartment(emp.getDepartment());
        data.setDesignation(emp.getDesignation());
        data.setEmployeeType(emp.getEmployeeType());
        data.setDateOfJoining(emp.getDateOfJoining());
        data.setDateOfExit(emp.getDateOfExit());
        data.setBasicSalary(emp.getBasicSalary());

        OrgPojo org = orgSvc.findById(user.getOrgId());
        data.setOrganizationName(org.getName());
        data.setOrgKey(org.getOrgKey());
        data.setIsManager(empSvc.existsManager(user.getId())); // true if anyone reports to this user

        // manager is optional (top-of-org employees have none) -- findByUserIdOrNull, not findByUserId
        EmpPojo mgr = empSvc.findByUserIdOrNull(emp.getManagerId());
        data.setManagerName(mgr != null ? mgr.getName() : null);
        return data;
    }
}
