package com.botfunnel.auth;

import com.botfunnel.auth.dto.AuthResponse;
import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.auth.dto.MeResponse;
import com.botfunnel.common.AppException;
import com.botfunnel.common.GlobalErrorHandler;
import com.botfunnel.common.metrics.MeterRegistryConfig;
import com.botfunnel.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// MVC slice tests: AuthService is mocked. Verifies the controller wiring and SecurityConfig
// path matchers. Full end-to-end auth flows are exercised in AuthControllerIT (Testcontainers).
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, GlobalErrorHandler.class, MeterRegistryConfig.class})
class AuthControllerSliceTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthService authService;

    @Test
    void login_success_returns200WithBody() throws Exception {
        AuthResponse response = new AuthResponse("u1", "user@test.com", "Alice", "active", null);
        when(authService.login(any(LoginRequest.class), any(), any())).thenReturn(response);

        ObjectMapper om = new ObjectMapper();
        String body = om.writeValueAsString(new LoginRequest("user@test.com", "password1", false));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u1"))
                .andExpect(jsonPath("$.email").value("user@test.com"))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void login_invalidCreds_returns401() throws Exception {
        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenThrow(AppException.unauthorized("Invalid credentials"));

        ObjectMapper om = new ObjectMapper();
        String body = om.writeValueAsString(new LoginRequest("user@test.com", "wrong", false));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void login_validationError_blankEmail_returns400() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String body = om.writeValueAsString(new LoginRequest("", "", false));
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void me_withoutSession_returns401() throws Exception {
        // /api/auth/** is permitAll, so the security chain does NOT gate /me — the controller is
        // reached and delegates to AuthService.me(), which itself raises AppException.unauthorized
        // when no authenticated principal is present (four-clause check at AuthService.java:143).
        // Stubbing the mock to throw mirrors that real path so GlobalErrorHandler emits 401 —
        // exercising the slice's controller-→-handler wiring without relying on the security
        // chain (the prior assertion silently passed via Mockito's null-return + ResponseEntity.ok,
        // a slice-fixture defect surfaced by the Task 16 audit, F-C1 #3).
        when(authService.me()).thenThrow(AppException.unauthorized("Not authenticated"));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not authenticated"));
    }

    @Test
    @WithMockUser
    void me_withSession_returns200WithUserBody() throws Exception {
        MeResponse me = new MeResponse("u1", "user@test.com", "Alice", "active");
        when(authService.me()).thenReturn(me);

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u1"))
                .andExpect(jsonPath("$.email").value("user@test.com"))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.status").value("active"));
    }
}
