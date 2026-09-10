package com.leaveapp.service;

// import flow: CaptchaService -> Google reCAPTCHA siteverify REST endpoint (external)
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaveapp.exception.BadRequestException;

// Google reCAPTCHA v3 (score-based, no user challenge) verification for the three public
// endpoints security-standards requires it on: signup, login, forgot-password. Disabled by
// default (app.captcha.enabled=false) so local dev and the test suite need no live
// credentials -- see app.properties. plain java.net.http.HttpClient, not a new dependency,
// since the project targets Java 21.
@Service
public class CaptchaService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);
    private static final String VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${app.captcha.enabled:false}")
    private boolean enabled;

    @Value("${app.captcha.secret:}")
    private String secret;

    // reCAPTCHA v3 score: 1.0 = very likely human, 0.0 = very likely a bot.
    @Value("${app.captcha.score-threshold:0.5}")
    private double scoreThreshold;

    /* Public Methods */

    // No-op when disabled. Otherwise throws BadRequestException for a missing token, a
    // failed/expired token, an action mismatch (the token was solved for a different
    // form), a score below threshold, or the verify call itself failing -- fails closed,
    // not open, since a broken CAPTCHA check must not become an open door.
    public void verify(String token, String action) {
        if (!enabled) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new BadRequestException("captcha verification failed");
        }
        try {
            String body = "secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8) //
                + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(VERIFY_URL)) //
                .header("Content-Type", "application/x-www-form-urlencoded") //
                .timeout(Duration.ofSeconds(5)) //
                .POST(BodyPublishers.ofString(body)) //
                .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(res.body());
            boolean success = json.path("success").asBoolean(false);
            boolean actionMatches = action.equals(json.path("action").asText(null));
            double score = json.path("score").asDouble(0);
            if (!success || !actionMatches || score < scoreThreshold) {
                log.warn("captcha rejected action={} success={} actionMatches={} score={}",
                    action, success, actionMatches, score);
                throw new BadRequestException("captcha verification failed");
            }
        } catch (IOException e) {
            log.error("captcha verification call failed", e);
            throw new BadRequestException("captcha verification failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("captcha verification call interrupted", e);
            throw new BadRequestException("captcha verification failed");
        }
    }
}
