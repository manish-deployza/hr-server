package com.leaveapp.security;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

@Component
public class RequestIdFilter implements Filter {

    /* Variables */

    public static final String REQ_ID_ATTR = "requestId";

    /* Public Methods */

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String reqId = UUID.randomUUID().toString();
        req.setAttribute(REQ_ID_ATTR, reqId);
        MDC.put("requestId", reqId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("requestId");
        }
    }
}
