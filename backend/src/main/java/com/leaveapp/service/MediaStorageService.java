package com.leaveapp.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaveapp.exception.BadRequestException;

// The one class that is allowed to know Google Cloud Storage exists -- gated by
// app.media.bucket (blank = disabled, same pattern as CaptchaService/RazorpayGatewayService).
// Plain HTTPS against the GCS JSON API with the VM service account's token from the
// metadata server: no cloud SDK on the classpath, so the WAR and heap stay small on the
// 1 GB production VM. Callers only ever see a bucket-relative key and bytes.
@Service
public class MediaStorageService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(MediaStorageService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    // Refresh the cached token this long before it really expires
    private static final long TOKEN_SKEW_SECONDS = 60;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    @Value("${app.media.bucket:}")
    private String bucket;

    @Value("${app.media.api-base:https://storage.googleapis.com}")
    private String apiBase;

    @Value("${app.media.token-url:http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token}")
    private String tokenUrl;

    /* Public Methods */

    public boolean isEnabled() {
        return bucket != null && !bucket.isBlank();
    }

    public void put(String key, byte[] bytes, String contentType) {
        URI uri = URI.create(apiBase + "/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=" + enc(key));
        HttpRequest req = authorized(uri).header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        HttpResponse<byte[]> res = send(req);
        if (res.statusCode() != 200) {
            log.error("GCS upload failed key={} status={}", key, res.statusCode());
            throw new IllegalStateException("media upload failed (HTTP " + res.statusCode() + ")");
        }
    }

    public byte[] get(String key) {
        HttpRequest req = authorized(URI.create(objectUrl(key) + "?alt=media")).GET().build();
        HttpResponse<byte[]> res = send(req);
        if (res.statusCode() == 404) {
            throw new BadRequestException("stored media is missing");
        }
        if (res.statusCode() != 200) {
            log.error("GCS download failed key={} status={}", key, res.statusCode());
            throw new IllegalStateException("media download failed (HTTP " + res.statusCode() + ")");
        }
        return res.body();
    }

    // Best effort: an orphaned object costs pennies, a failed delete must not fail the request
    public void delete(String key) {
        try {
            HttpResponse<byte[]> res = send(authorized(URI.create(objectUrl(key))).DELETE().build());
            if (res.statusCode() != 204 && res.statusCode() != 404) {
                log.warn("GCS delete failed key={} status={}", key, res.statusCode());
            }
        } catch (RuntimeException e) {
            log.warn("GCS delete failed key={}", key, e);
        }
    }

    /* Private Helper Methods */

    private String objectUrl(String key) {
        return apiBase + "/storage/v1/b/" + bucket + "/o/" + enc(key);
    }

    private HttpRequest.Builder authorized(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Authorization", "Bearer " + accessToken());
    }

    private HttpResponse<byte[]> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("media storage unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("media storage call interrupted", e);
        }
    }

    private synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpiry)) {
            return token;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(TIMEOUT)
            .header("Metadata-Flavor", "Google").GET().build();
        HttpResponse<byte[]> res = send(req);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("could not get a storage token (HTTP " + res.statusCode() + ")");
        }
        try {
            JsonNode json = mapper.readTree(new String(res.body(), StandardCharsets.UTF_8));
            token = json.get("access_token").asText();
            tokenExpiry = Instant.now().plusSeconds(json.get("expires_in").asLong() - TOKEN_SKEW_SECONDS);
            return token;
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("unreadable storage token response", e);
        }
    }

    private static String enc(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8);
    }
}
