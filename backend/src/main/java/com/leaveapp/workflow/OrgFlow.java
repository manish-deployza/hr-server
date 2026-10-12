package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// OrgFlow -> OrgService -> OrgRepo -> DB (settings read/update)
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.data.OrgData;
import com.leaveapp.model.form.OrgForm;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.service.OrgService;

@Service
public class OrgFlow {

    /* Variables */

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OrgData get(Long orgId) {
        return toData(orgSvc.findById(orgId));
    }

    @Transactional(rollbackFor = Exception.class)
    public OrgData update(Long orgId, OrgForm form) {
        return toData(orgSvc.update(orgId, form.getName(), form.getHrNotifyEmail()));
    }

    /* Private Helper Methods */

    // *Pojo (entity) -> *Data (API shape) -- never return a *Pojo past this Flow
    private OrgData toData(OrgPojo org) {
        OrgData data = new OrgData();
        data.setId(org.getId());
        data.setName(org.getName());
        data.setOrgKey(org.getOrgKey());
        data.setHrNotifyEmail(org.getHrNotifyEmail());
        data.setStatus(org.getStatus().name());
        return data;
    }
}
