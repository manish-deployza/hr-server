package com.leaveapp.model.data;

import lombok.Getter;
import lombok.Setter;

// API shape for the org-settings screen: the editable fields plus the read-only
// identity/lifecycle ones the UI displays.
@Getter
@Setter
public class OrgData {

    /* Variables */

    private Long id;
    private String name;
    private String orgKey;
    private String hrNotifyEmail;
    private String status;
}
