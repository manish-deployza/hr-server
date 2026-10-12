package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// EmpSensitiveService -> EmpSensitiveRepo -> DB (the P2-P4 sensitive-module row)
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.enums.BloodGroup;
import com.leaveapp.pojo.AddressPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.repo.EmpSensitiveRepo;

// Directory Steps 4/5/6/9 (Personal, Address, Pay & IDs, Voluntary) -- kept separate from
// EmpService (already 227 lines; "employee profile" plus "sensitive profile" is
// exactly the second responsibility the class-cohesion rule flags). Same
// null-means-unchanged PATCH semantics as EmpService.applyAdminFields/
// applyDirectoryFields. Cross-field validation (address required-together, emergency
// contact all-or-none) lives in DirectoryFlow, which reads the fields back off the
// entity after these apply* calls -- this service only merges.
@Service
public class EmpSensitiveService {

    /* Variables */

    // E.164 numbers are at most 15 digits; 20 leaves room for a leading "+" and
    // formatting (spaces/dashes) without accepting anything wildly oversized. Matches
    // EmpSensitivePojo.phone's column length.
    private static final int MAX_PHONE = 20;

    // matches EmpSensitivePojo.contactAddress's column length.
    private static final int MAX_CONTACT_ADDRESS = 255;

    @Autowired
    private EmpSensitiveRepo sensRepo;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public EmpSensitivePojo findByUserIdOrNull(Long userId) {
        return sensRepo.findById(userId).orElse(null);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Map<Long, EmpSensitivePojo> findByUserIds(Collection<Long> userIds) {
        List<EmpSensitivePojo> found = sensRepo.findByUserIdIn(userIds);
        return found.stream().collect(Collectors.toMap(EmpSensitivePojo::getUserId, Function.identity()));
    }

    // PATCH semantics: a null field means "leave unchanged"; a present field
    // (including "") is trimmed, length-checked, and written. Moved here from
    // EmpService.updateProfile/clean -- phone/address are PII, not profile data.
    @Transactional(rollbackFor = Exception.class)
    public void applyContactFields(EmpSensitivePojo e, String phone, String contactAddress) {
        if (phone != null) {
            e.setPhone(clean("phone", phone, MAX_PHONE));
        }
        if (contactAddress != null) {
            e.setContactAddress(clean("address", contactAddress, MAX_CONTACT_ADDRESS));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public EmpSensitivePojo createEmpty(Long userId) {
        Instant now = Instant.now();
        EmpSensitivePojo e = new EmpSensitivePojo();
        e.setUserId(userId);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return sensRepo.save(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyPersonalFields(EmpSensitivePojo e, LocalDate dateOfBirth, String gender,
        String nationality, String maritalStatus, String bloodGroup, String personalEmail) {
        if (dateOfBirth != null) {
            e.setDateOfBirth(dateOfBirth);
        }
        if (gender != null) {
            e.setGender(gender);
        }
        if (nationality != null) {
            e.setNationality(nationality);
        }
        if (maritalStatus != null) {
            e.setMaritalStatus(maritalStatus);
        }
        if (bloodGroup != null) {
            e.setBloodGroup(BloodGroup.fromLabel(bloodGroup)); // "" clears
        }
        if (personalEmail != null) {
            e.setPersonalEmail(personalEmail);
        }
    }

    // `incoming` carries only the fields the caller sent (nulls elsewhere); merged
    // field-by-field into e's existing embedded address, same null-means-unchanged
    // convention as every other apply* method here.
    @Transactional(rollbackFor = Exception.class)
    public void applyAddressFields(EmpSensitivePojo e, AddressPojo incoming,
        String emergencyName, String emergencyRelationship, String emergencyPhone) {
        AddressPojo addr = e.getAddress();
        if (incoming.getAddress1() != null) {
            addr.setAddress1(incoming.getAddress1());
        }
        if (incoming.getAddress2() != null) {
            addr.setAddress2(incoming.getAddress2());
        }
        if (incoming.getCity() != null) {
            addr.setCity(incoming.getCity());
        }
        if (incoming.getLandmark() != null) {
            addr.setLandmark(incoming.getLandmark());
        }
        if (incoming.getPostalCode() != null) {
            addr.setPostalCode(incoming.getPostalCode());
        }
        if (incoming.getState() != null) {
            addr.setState(incoming.getState());
        }
        if (incoming.getCountry() != null) {
            addr.setCountry(incoming.getCountry());
        }
        if (emergencyName != null) {
            e.setEmergencyName(emergencyName);
        }
        if (emergencyRelationship != null) {
            e.setEmergencyRelationship(emergencyRelationship);
        }
        if (emergencyPhone != null) {
            e.setEmergencyPhone(emergencyPhone);
        }
    }

    // bankAccount/nationalId/taxId/passport are stored plain -- masked to their last 4
    // characters only in DirectoryData (see DirectoryFlow.maskTail), never encrypted at
    // rest.
    @Transactional(rollbackFor = Exception.class)
    public void applyPayFields(EmpSensitivePojo e, String bankAccount, String bankIfsc,
        String nationalId, String taxId, String passport) {
        if (bankAccount != null) {
            e.setBankAccount(bankAccount);
        }
        if (bankIfsc != null) {
            e.setBankIfsc(bankIfsc);
        }
        if (nationalId != null) {
            e.setNationalId(nationalId);
        }
        if (taxId != null) {
            e.setTaxId(taxId);
        }
        if (passport != null) {
            e.setPassport(passport);
        }
    }

    /* Private Helper Methods */

    private static String clean(String field, String value, int maxLen) {
        String trimmed = value.trim();
        if (trimmed.length() > maxLen) {
            throw new BadRequestException(field + " must be at most " + maxLen + " characters");
        }
        return trimmed;
    }
}
