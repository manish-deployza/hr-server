package com.leaveapp.model.form;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

// Request body for POST /api/hr/employees/import -- the browser parses the uploaded
// CSV/TSV file client-side and posts the rows as JSON; no file upload here.
@Getter
@Setter
public class EmployeeImportForm {

    /* Variables */

    private List<EmployeeImportRowForm> rows;
}
