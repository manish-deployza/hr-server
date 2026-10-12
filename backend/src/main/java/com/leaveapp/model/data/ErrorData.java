package com.leaveapp.model.data;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErrorData {

    /* Variables */

    private String summary;
    private String details;
    private List<String> stackTrace;

    // Optional: only the filter-level 401 (RestAuthEntryPoint, ahead of
    // GlobalExceptionHandler) populates these today -- see its class comment. code is a
    // machine-readable discriminant (e.g. "AUTHN_REQUIRED") for the frontend to branch on
    // instead of string-matching summary; traceId/timestamp are what a user quotes in a
    // support ticket, correlated against the "requestId" MDC key in the log line.
    private String code;
    private String traceId;
    private String timestamp;

    /* Constructors */

    public ErrorData(String summary) {
        this.summary = summary;
    }
}
