package com.botfunnel.common;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalErrorHandlerTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    @Test
    void appException_400_returnsBadRequest() {
        AppException ex = AppException.badRequest("invalid input");

        ResponseEntity<ErrorResponse> response = handler.handleAppException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("invalid input");
    }

    @Test
    void appException_401_returnsUnauthorized() {
        ResponseEntity<ErrorResponse> response = handler.handleAppException(AppException.unauthorized("not logged in"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void appException_403_returnsForbidden() {
        ResponseEntity<ErrorResponse> response = handler.handleAppException(AppException.forbidden("access denied"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void appException_409_returnsConflict() {
        ResponseEntity<ErrorResponse> response = handler.handleAppException(AppException.conflict("already exists"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void appException_429_returnsTooManyRequests() {
        ResponseEntity<ErrorResponse> response = handler.handleAppException(AppException.tooManyRequests("slow down"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void throwable_returns500_withoutExposingDetails() {
        ResponseEntity<ErrorResponse> response = handler.handleThrowable(new RuntimeException("db connection timeout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
        assertThat(response.getBody().message()).doesNotContain("db connection timeout");
    }

    @Test
    void handleMethodArgumentNotValid_returnsBadRequestWithJoinedFieldErrors_codeNull() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", "email", "must not be blank"));
        result.addError(new FieldError("target", "password", "size must be between 8 and 64"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(stubMethodParameter(), result);

        ResponseEntity<ErrorResponse> response = handler.handleBindException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isNull();
        assertThat(response.getBody().message())
                .isEqualTo("email: must not be blank, password: size must be between 8 and 64");
    }

    @Test
    void handleMethodArgumentNotValid_combinesGlobalAndFieldErrors() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", "email", "must not be blank"));
        result.addError(new ObjectError("target", "passwords must match"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(stubMethodParameter(), result);

        ResponseEntity<ErrorResponse> response = handler.handleBindException(ex);

        // Order: field errors first, then global errors — matches Stream.concat(fieldErrors, globalErrors).
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("email: must not be blank, target: passwords must match");
    }

    @Test
    void handleResponseStatus_propagatesStatusAndReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too big");

        ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("too big");
        assertThat(response.getBody().code()).isNull();
    }

    @SuppressWarnings("unused")
    private void dummyTarget(Object arg) {}

    private MethodParameter stubMethodParameter() throws NoSuchMethodException {
        Method method = GlobalErrorHandlerTest.class.getDeclaredMethod("dummyTarget", Object.class);
        return new MethodParameter(method, 0);
    }
}
