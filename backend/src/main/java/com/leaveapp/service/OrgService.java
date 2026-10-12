package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// OrgService -> OrgRepo -> DB (create/lookup tenant rows)
import java.time.Instant;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.model.enums.OrgStatus;
import com.leaveapp.repo.OrgRepo;

@Service
public class OrgService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(OrgService.class);
    // signup lets a user pick their own org key; lowercase letters, digits,
    // '-' and '_' allowed
    private static final Pattern ORG_KEY = Pattern.compile("^[a-z0-9_-]{1,64}$");

    @Autowired
    private OrgRepo orgRepo;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OrgPojo findById(Long id) {
        return orgRepo.findById(id) //
            .orElseThrow(() -> new NotFoundException("organization not found id=" + id));
    }

    // used at login/signup to resolve orgKey -> org; null on miss, matches
    // EmpService.findByUserIdOrNull's null-on-miss convention
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OrgPojo findByOrgKeyOrNull(String orgKey) {
        return orgRepo.findByOrgKey(orgKey).orElse(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrgPojo create(String name, String orgKey, String hrNotifyEmail, String country) {
        if (!ORG_KEY.matcher(orgKey).matches()) {
            throw new BadRequestException("orgKey must be 1-64 lowercase letters, digits, hyphens(-), or underscores(_)");
        }
        if (orgRepo.findByOrgKey(orgKey).isPresent()) {
            throw new ConflictException("orgKey already in use");
        }
        OrgPojo org = new OrgPojo();
        org.setName(name);
        org.setOrgKey(orgKey);
        org.setHrNotifyEmail(hrNotifyEmail);
        org.setCountry(country);
        org.setStatus(OrgStatus.ACTIVE);
        Instant now = Instant.now();
        org.setCreatedAt(now);
        org.setUpdatedAt(now);
        OrgPojo saved = orgRepo.save(org);
        log.info("organization created id={} orgKey={}", saved.getId(), orgKey);
        return saved;
    }

    // PATCH semantics: a null arg means "leave this field alone". orgKey/status are
    // not touchable here -- orgKey is the login key, status is a lifecycle field.
    @Transactional(rollbackFor = Exception.class)
    public OrgPojo update(Long id, String name, String hrNotifyEmail) {
        OrgPojo org = findById(id);
        if (name != null) {
            String trimmed = name.trim();
            org.setName(trimmed.isEmpty() ? null : trimmed); // name is nullable
        }
        if (hrNotifyEmail != null) {
            String trimmed = hrNotifyEmail.trim();
            if (trimmed.isEmpty() || !trimmed.contains("@")) {
                throw new BadRequestException("hrNotifyEmail must be a valid email address");
            }
            org.setHrNotifyEmail(trimmed);
        }
        log.info("organization updated id={}", id);
        return org;
    }
}
