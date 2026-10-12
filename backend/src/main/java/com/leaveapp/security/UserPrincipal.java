package com.leaveapp.security;

import java.util.List;

import com.leaveapp.model.enums.UserRole;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserPrincipal {

    /* Variables */

    private Long userId;
    private Long orgId;
    private List<UserRole> roles;

    // PENDING user: holds only ROLE_ONBOARDING (see JwtAuthFilter), so every
    // @PreAuthorize outside /api/onboarding/** rejects the session.
    private boolean onboarding;
}
