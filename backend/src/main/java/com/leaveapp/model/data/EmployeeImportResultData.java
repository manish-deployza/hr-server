package com.leaveapp.model.data;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

// Response for POST /api/hr/employees/import. `skipped` holds pre-formatted
// "row N (email): reason" strings; `credentials` covers only the rows actually created.
@Getter
@Setter
public class EmployeeImportResultData {

    /* Variables */

    private int created;
    private List<String> skipped;
    private List<ImportCredentialData> credentials;
}
