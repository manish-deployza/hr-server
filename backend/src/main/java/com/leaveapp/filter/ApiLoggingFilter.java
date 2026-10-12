package com.leaveapp.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiLoggingFilter extends OncePerRequestFilter {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingFilter.class);
    private static final String API_PREFIX = "/api/";

    /* Protected Methods */

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!req.getRequestURI().startsWith(API_PREFIX)) {
            chain.doFilter(req, res);
            return;
        }

        long start = System.currentTimeMillis();
        log.info("start method={} uri={}", req.getMethod(), req.getRequestURI());
        try {
            chain.doFilter(req, res);
        } finally {
            long durMs = System.currentTimeMillis() - start;
            log.info("end method={} uri={} status={} durMs={}", //
                req.getMethod(), req.getRequestURI(), res.getStatus(), durMs);
        }
    }
}
