package com.leaveapp.rest;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;

// @PreAuthorize is a Spring AOP feature: it only fires when a method is invoked through
// a Spring-managed proxy inside a context with @EnableMethodSecurity (SecurityConfig).
// These *ControllerTest classes construct controllers with plain `new` -- no Spring
// context, TestConfig deliberately excludes controllers (see spring-review.md) -- so a
// bare instance never enforces @PreAuthorize at all. This wraps one in the same kind of
// proxy @EnableMethodSecurity would build, without needing an ApplicationContext:
// AuthorizationManagerBeforeMethodInterceptor.preAuthorize() is an Advisor (it carries
// its own Pointcut matching @PreAuthorize-annotated methods/classes), so it must be
// added via addAdvisor -- addAdvice would apply it to every method, annotated or not.
final class SecurityProxyTestSupport {

    /* Constructors */

    private SecurityProxyTestSupport() {
    }

    /* Package Methods */

    @SuppressWarnings("unchecked")
    static <T> T withPreAuthorize(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return (T) factory.getProxy();
    }
}
