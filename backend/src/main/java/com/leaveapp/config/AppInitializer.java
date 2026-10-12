package com.leaveapp.config;

import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletRegistration;

/**
 * Replaces web.xml. Filter order matches cross-cutting.md exactly: RequestIdFilter and
 * ApiLoggingFilter run ahead of the Spring Security chain, which is registered here as a
 * plain DelegatingFilterProxy rather than via AbstractSecurityWebApplicationInitializer,
 * so that ordering is explicit instead of relying on that class's default insertion point.
 */
public class AppInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {

    /* Protected Methods */

    @Override
    protected Class<?>[] getRootConfigClasses() {
        return new Class<?>[] { AppConfig.class };
    }

    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class<?>[] { WebConfig.class };
    }

    @Override
    protected String[] getServletMappings() {
        return new String[] { "/" };
    }

    @Override
    protected Filter[] getServletFilters() {
        return new Filter[] { //
            new DelegatingFilterProxy("requestIdFilter"), //
            new DelegatingFilterProxy("apiLoggingFilter"), //
            new DelegatingFilterProxy("springSecurityFilterChain") //
        };
    }

    // Servlet-level ceiling for PUT /api/employees/me/photo -- a safety net above the
    // app.photo.max-bytes check in EmpService (that one is configurable and gives a
    // clean 400; this one just stops an oversized request before it's even buffered). No
    // Spring context exists yet at this point, hence the plain constant rather than
    // @Value.
    @Override
    protected void customizeRegistration(ServletRegistration.Dynamic registration) {
        registration.setMultipartConfig(new MultipartConfigElement(null, 5 * 1024 * 1024, 5 * 1024 * 1024, 0));
    }
}
