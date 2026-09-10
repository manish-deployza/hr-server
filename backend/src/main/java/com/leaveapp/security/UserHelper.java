package com.leaveapp.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class UserHelper {

    /* Constructors */

    private UserHelper() {
    }

    /* Public Methods */

    public static UserPrincipal getPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p;
        }
        return null;
    }

    public static Long getUserId() {
        UserPrincipal p = getPrincipal();
        return p != null ? p.getUserId() : null;
    }

    public static Long getOrganizationId() {
        UserPrincipal p = getPrincipal();
        return p != null ? p.getOrganizationId() : null;
    }
}
