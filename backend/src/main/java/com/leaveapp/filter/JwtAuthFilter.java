package com.leaveapp.filter;

// import flow:
// JwtAuthFilter -> CookieUtil (util, reads the session cookie off the request)
// JwtAuthFilter -> JwtService (service, validates the token and extracts claims)
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.leaveapp.pojo.enums.UserRole;
import com.leaveapp.security.TenantContext;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.JwtService;
import com.leaveapp.util.CookieUtil;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Replaces UserAuthFilter. JwtService.parseOrNull does one DB read (UserService.
// isTokenValid) to catch a revoked session -- logout, deactivation, a role or
// password change -- everything else (org id, role) is trusted straight off the
// token's claims for its lifetime; see prompts/features/multi-tenancy.md.
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /* Variables */

    @Autowired
    private JwtService jwtSvc;

    @Value("${app.cookie.name}")
    private String cookieName;

    /* Protected Methods */

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        try {
            String token = CookieUtil.read(req, cookieName);
            if (token != null) {
                UserPrincipal principal = jwtSvc.parseOrNull(token);
                if (principal != null) {
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    for (UserRole role : principal.getRoles()) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
                    }
                    UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    TenantContext.set(principal.getOrgId());
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
