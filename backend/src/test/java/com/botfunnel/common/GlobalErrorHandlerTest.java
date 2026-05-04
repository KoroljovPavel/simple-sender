package com.botfunnel.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
}
