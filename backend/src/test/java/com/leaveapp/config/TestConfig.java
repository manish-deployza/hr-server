package com.leaveapp.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Controller;

/**
 * Same scan as AppConfig, minus AppConfig itself (per jpa-persistence.md), plus every
 * class that needs a real servlet ApplicationContext to initialize -- SecurityConfig
 * (@EnableWebSecurity), WebConfig (@EnableWebMvc), and controllers. Repository tests run
 * in a plain, non-web context, so those would fail to wire up here regardless.
 */
@Configuration
@ComponentScan(
    basePackages = "com.leaveapp",
    excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = { AppConfig.class, SecurityConfig.class, WebConfig.class }
        ),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class)
    }
)
@PropertySource("classpath:test.properties")
public class TestConfig {
}
