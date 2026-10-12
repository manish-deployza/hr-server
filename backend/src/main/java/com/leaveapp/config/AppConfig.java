package com.leaveapp.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;

/**
 * Root context: component-scans everything except {@code @Controller}/{@code @RestController}
 * classes (those live in the servlet context via {@link WebConfig}), and loads production
 * properties. Declares no beans -- see architecture.md.
 *
 * <p>{@code @EnableScheduling} lives here, not on a class TestConfig also scans, so
 * {@code @Scheduled} methods (scheduler.EmailSender) only actually run in the real
 * webapp -- TestConfig excludes AppConfig itself, so the test context never registers
 * the scheduling infrastructure, even though EmailSender is still a plain bean there.
 */
@Configuration
@EnableScheduling
@ComponentScan(
    basePackages = "com.leaveapp",
    excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class)
)
@PropertySource("classpath:app.properties")
public class AppConfig {
}
