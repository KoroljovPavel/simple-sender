package com.botfunnel.auth;

import com.botfunnel.auth.dto.AuthResponse;
import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.auth.dto.MeResponse;
import com.botfunnel.common.AppException;
import com.botfunnel.common.GlobalErrorHandler;
import com.botfunnel.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

// WebFlux slice tests: AuthService is mocked. Verifies the controller wiring and SecurityConfig
// path matchers. Full end-to-end auth flows are exercised in AuthControllerIT (Testcontainers).
@WebFluxTest(controllers = AuthController.class)
@Import({SecurityConfig.class, GlobalErrorHandler.class})
class AuthControllerSliceTest {

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    AuthService authService;

    @Test
    void login_success_returns200WithBody() {
        AuthResponse response = new AuthResponse("u1", "user@test.com", "Alice", "active", null);
        when(authService.login(any(LoginRequest.class), any())).thenReturn(Mono.just(response));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest("user@test.com", "password1", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("u1")
                .jsonPath("$.email").isEqualTo("user@test.com")
                .jsonPath("$.name").isEqualTo("Alice")
                .jsonPath("$.status").isEqualTo("active");
    }

    @Test
    void login_invalidCreds_returns401() {
        when(authService.login(any(LoginRequest.class), any()))
                .thenReturn(Mono.error(AppException.unauthorized("Invalid credentials")));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest("user@test.com", "wrong", false))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid credentials");
    }

    @Test
    void login_validationError_blankEmail_returns400() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String body = om.writeValueAsString(new LoginRequest("", "", false));
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void me_withoutSession_returns401() {
        when(authService.me()).thenReturn(Mono.error(AppException.unauthorized("Not authenticated")));

        webTestClient.get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @WithMockUser
    void me_withSession_returns200WithUserBody() {
        MeResponse me = new MeResponse("u1", "user@test.com", "Alice", "active");
        when(authService.me()).thenReturn(Mono.just(me));

        webTestClient.get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("u1")
                .jsonPath("$.email").isEqualTo("user@test.com")
                .jsonPath("$.name").isEqualTo("Alice")
                .jsonPath("$.status").isEqualTo("active");
    }
}
