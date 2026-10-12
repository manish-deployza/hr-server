 package com.leaveapp.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.leaveapp.filter.JwtAuthFilter;
import com.leaveapp.security.RestAuthEntryPoint;

// Role gating lives on each *Controller as @PreAuthorize (enabled below); this chain
// only handles authentication (JwtAuthFilter) and the public/authenticated split --
// see AbstractController for the reasoning.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /* Variables */

    @Autowired
    private JwtAuthFilter authFilter;

    @Autowired
    private RestAuthEntryPoint entryPoint;

    // Comma-separated browser origins allowed to call the API with credentials.
    // Defaults to the local jetty frontend; override with app.cors.allowed-origins.
    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private List<String> allowedOrigins;

    /* Public Methods */

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http //
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) //
            .cors(c -> c.configurationSource(corsConfigurationSource())) //
            // Safe to disable: the session cookie is sent SameSite=Strict (default,
            // AbstractController.buildCookieHeader / app.cookie.same-site), which is this
            // app's chosen CSRF control instead of a token. Do not disable this without
            // also relaxing SameSite -- see that property's comment in app.properties.
            .csrf(csrf -> csrf.disable()) //
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class) //
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint)) //
            .authorizeHttpRequests(auth -> auth //
                .requestMatchers(HttpMethod.POST, "/api/auth/signup").permitAll() //
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll() //
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll() //
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll() //
                .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll() //
                .requestMatchers(HttpMethod.POST, "/api/auth/set-password").permitAll() //
                .requestMatchers(HttpMethod.GET, "/api/auth/verify-email").permitAll() //
                // Gateway call, no user session -- authenticated by its own HMAC
                // signature check in BillingWebhookController, not the JWT filter.
                .requestMatchers(HttpMethod.POST, "/api/billing/webhook/razorpay").permitAll() //
                // Role gating is @PreAuthorize on the controllers from here down; this is
                // just "reached the app => must be authenticated".
                .anyRequest().authenticated() //
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
