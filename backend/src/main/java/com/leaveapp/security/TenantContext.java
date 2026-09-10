package com.leaveapp.security;

// Plain ThreadLocal, not SecurityContextHolder -- set by JwtAuthFilter (which copies
// UserPrincipal.organizationId here after authenticating) purely so the planned Hibernate
// tenant filter can read the current org id without reaching into the security context;
// see prompts/features/multi-tenancy.md. No current callers of get().
public class TenantContext {

    /* Variables */

    private static final ThreadLocal<Long> ORG_ID = new ThreadLocal<>();

    /* Constructors */

    private TenantContext() {
    }

    /* Public Methods */

    public static void set(Long organizationId) {
        ORG_ID.set(organizationId);
    }

    public static Long get() {
        return ORG_ID.get();
    }

    public static void clear() {
        ORG_ID.remove();
    }
}
