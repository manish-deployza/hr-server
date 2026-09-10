package com.leaveapp.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiLoggingFilter implements Filter {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingFilter.class);
    private static final String API_PREFIX = "/api/";

    /* Public Methods */

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpRes = (HttpServletResponse) res;

        if (!httpReq.getRequestURI().startsWith(API_PREFIX)) {
            chain.doFilter(req, res);
            return;
        }

        long start = System.currentTimeMillis();
        log.info("start method={} uri={}", httpReq.getMethod(), httpReq.getRequestURI());
        try {
            chain.doFilter(req, res);
        } finally {
            long durMs = System.currentTimeMillis() - start;
            log.info("end method={} uri={} status={} durMs={}", //
                httpReq.getMethod(), httpReq.getRequestURI(), httpRes.getStatus(), durMs);
        }
    }
}
