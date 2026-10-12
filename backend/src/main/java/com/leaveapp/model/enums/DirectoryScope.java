package com.leaveapp.model.enums;

// GET /api/directory read scope, resolved server-side in DirectoryController from the
// caller's role and manager-graph position -- never a request parameter. Order matters
// where they overlap (an ORG_ADMIN who also has direct reports still gets ORG).
public enum DirectoryScope {
    ORG,
    REPORTS,
    SELF
}
