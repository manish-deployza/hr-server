package com.leaveapp.pojo;

// account-level RBAC role -- see prompts/schema.md#user. No PENDING: the
// HR-creates-employee endpoint decides the role at create time.
public enum UserRole {
    ORG_ADMIN,
    HR_ADMIN,
    EMPLOYEE
}
