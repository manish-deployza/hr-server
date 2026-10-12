package com.leaveapp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.ErrorData;

// Plain unit test: every handler's response body (fixed generic text for framework and
// unexpected errors so internals never leak; the exception's own message only for our
// deliberate 400/409/401/403 exceptions) and the HTTP status declared on it. Handler
// methods are looked up by name so the @ResponseStatus mapping is asserted, not just the body.
public class GlobalExceptionHandlerTest {

    /* Variables */

    private static final String BAD_REQUEST_MSG = "Invalid request. Check the submitted values.";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /* Public Methods */

    @Test
    public void onBadJson_400WithFixedMessage() {
        ErrorData d = handler.onBadJson(new HttpMessageNotReadableException("secret parser detail", new MockHttpInputMessage(new byte[0])));
        assertBody(d, "Malformed request body");
        assertStatus("onBadJson", HttpStatus.BAD_REQUEST, HttpMessageNotReadableException.class);
    }

    @Test
    public void onInvalid_400WithFixedMessage() throws NoSuchMethodException {
        Method m = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
            new MethodParameter(m, 0), new BeanPropertyBindingResult(new Object(), "form"));
        assertBody(handler.onInvalid(ex), BAD_REQUEST_MSG);
        assertStatus("onInvalid", HttpStatus.BAD_REQUEST, MethodArgumentNotValidException.class);
    }

    @Test
    public void onMissingParam_400WithFixedMessage() {
        assertBody(handler.onMissingParam(new MissingServletRequestParameterException("year", "Integer")), BAD_REQUEST_MSG);
        assertStatus("onMissingParam", HttpStatus.BAD_REQUEST, MissingServletRequestParameterException.class);
    }

    @Test
    public void onTypeMismatch_400WithFixedMessage() throws NoSuchMethodException {
        Method m = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
            "abc", Integer.class, "year", new MethodParameter(m, 0), new NumberFormatException("abc"));
        assertBody(handler.onTypeMismatch(ex), BAD_REQUEST_MSG);
        assertStatus("onTypeMismatch", HttpStatus.BAD_REQUEST, MethodArgumentTypeMismatchException.class);
    }

    @Test
    public void onUploadTooLarge_400WithFixedMessage() {
        assertBody(handler.onUploadTooLarge(new MaxUploadSizeExceededException(1024L)), BAD_REQUEST_MSG);
        assertStatus("onUploadTooLarge", HttpStatus.BAD_REQUEST, MaxUploadSizeExceededException.class);
    }

    @Test
    public void onBadRequest_400WithExceptionMessage() {
        assertBody(handler.onBadRequest(new BadRequestException("email already used")), "email already used");
        assertStatus("onBadRequest", HttpStatus.BAD_REQUEST, BadRequestException.class);
    }

    @Test
    public void onConflict_409WithExceptionMessage() {
        assertBody(handler.onConflict(new ConflictException("seat limit reached")), "seat limit reached");
        assertStatus("onConflict", HttpStatus.CONFLICT, ConflictException.class);
    }

    @Test
    public void onNotFound_404WithFixedMessage_notTheInternalOne() {
        assertBody(handler.onNotFound(new NotFoundException("employee not found id=9")), "Not found");
        assertStatus("onNotFound", HttpStatus.NOT_FOUND, NotFoundException.class);
    }

    @Test
    public void onNoHandler_404WithFixedMessage() {
        assertBody(handler.onNoHandler(new NoHandlerFoundException("GET", "/api/nope", HttpHeaders.EMPTY)), "Not found");
        assertStatus("onNoHandler", HttpStatus.NOT_FOUND, NoHandlerFoundException.class);
    }

    @Test
    public void onMethodNotSupported_405WithFixedMessage() {
        assertBody(handler.onMethodNotSupported(new HttpRequestMethodNotSupportedException("TRACE")), "Method not allowed");
        assertStatus("onMethodNotSupported", HttpStatus.METHOD_NOT_ALLOWED, HttpRequestMethodNotSupportedException.class);
    }

    @Test
    public void onAuthFail_401WithExceptionMessage() {
        assertBody(handler.onAuthFail(new NotAuthException("not authenticated")), "not authenticated");
        assertBody(handler.onAuthFail(new BadCredentialsException("bad creds")), "bad creds");
        assertStatus("onAuthFail", HttpStatus.UNAUTHORIZED, org.springframework.security.core.AuthenticationException.class);
    }

    @Test
    public void onForbidden_403WithExceptionMessage() {
        assertBody(handler.onForbidden(new AccessDeniedException("only HR can act on this step")), "only HR can act on this step");
        assertStatus("onForbidden", HttpStatus.FORBIDDEN, AccessDeniedException.class);
    }

    @Test
    public void onError_500WithGenericMessage_neverTheExceptionText() {
        ErrorData d = handler.onError(new IllegalStateException("jdbc:mysql://secret-host failure"));
        assertBody(d, "Something went wrong. Please try again.");
        assertStatus("onError", HttpStatus.INTERNAL_SERVER_ERROR, Exception.class);
    }

    @Test
    public void onError_nullMessageException_stillGeneric() {
        assertBody(handler.onError(new NullPointerException()), "Something went wrong. Please try again.");
    }

    /* Private Helper Methods */

    private static void assertBody(ErrorData d, String summary) {
        assertNotNull(d);
        assertEquals(summary, d.getSummary());
        assertNull(d.getDetails());
        assertNull(d.getStackTrace());
    }

    private static void assertStatus(String handlerName, HttpStatus expected, Class<?> exceptionType) {
        for (Method m : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (m.getName().equals(handlerName)) {
                ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(m, ResponseStatus.class);
                assertNotNull(rs, handlerName);
                assertEquals(expected, rs.value(), handlerName);
                assertEquals(exceptionType, m.getParameterTypes()[0], handlerName);
                return;
            }
        }
        throw new AssertionError("no handler " + handlerName);
    }

    // Only used as a MethodParameter source for the framework exceptions above.
    @SuppressWarnings("unused")
    private void dummy(String arg) {
    }
}
