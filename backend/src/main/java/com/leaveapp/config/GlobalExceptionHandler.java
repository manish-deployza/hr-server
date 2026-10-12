package com.leaveapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.ErrorData;

/**
 * Converts exceptions from {@code @RestController} classes (and the Flow/Service layers
 * they call) into {@code {"summary": "..."}} JSON. Does not intercept exceptions from the
 * Spring Security filter chain -- see cross-cutting.md's "Known Limitation" section.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BAD_JSON_MSG = "Malformed request body";
    private static final String BAD_REQUEST_MSG = "Invalid request. Check the submitted values.";
    private static final String NOT_FOUND_MSG = "Not found";
    private static final String METHOD_NOT_ALLOWED_MSG = "Method not allowed";
    private static final String SERVER_ERR_MSG = "Something went wrong. Please try again.";

    /* Public Methods */

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorData onBadJson(HttpMessageNotReadableException ex) {
        log.warn("malformed request body: {}", ex.getMessage());
        return new ErrorData(BAD_JSON_MSG);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorData onInvalid(MethodArgumentNotValidException ex) {
        log.warn("invalid request body: {}", ex.getMessage());
        return new ErrorData(BAD_REQUEST_MSG);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorData onMissingParam(MissingServletRequestParameterException ex) {
        log.warn("missing request parameter: {}", ex.getMessage());
        return new ErrorData(BAD_REQUEST_MSG);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorData onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("request parameter type mismatch: {}", ex.getMessage());
        return new ErrorData(BAD_REQUEST_MSG);
    }

    // Servlet-level multipart ceiling (AppInitializer) tripped -- otherwise falls
    // through to the generic 500 below.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorData onUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("upload too large: {}", ex.getMessage());
        return new ErrorData(BAD_REQUEST_MSG);
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorData onBadRequest(BadRequestException ex) {
        return new ErrorData(ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorData onConflict(ConflictException ex) {
        return new ErrorData(ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorData onNotFound(NotFoundException ex) {
        log.warn("not found: {}", ex.getMessage());
        return new ErrorData(NOT_FOUND_MSG);
    }

    // Unknown route -- 404, not the generic 500 from onError below.
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorData onNoHandler(NoHandlerFoundException ex) {
        log.warn("no handler: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return new ErrorData(NOT_FOUND_MSG);
    }

    // Right path, wrong verb -- 405, not the generic 500 from onError below.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorData onMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("method not supported: {}", ex.getMessage());
        return new ErrorData(METHOD_NOT_ALLOWED_MSG);
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorData onAuthFail(AuthenticationException ex) {
        return new ErrorData(ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorData onForbidden(AccessDeniedException ex) {
        return new ErrorData(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorData onError(Exception ex) {
        log.error("unhandled error", ex);
        return new ErrorData(SERVER_ERR_MSG);
    }
}
