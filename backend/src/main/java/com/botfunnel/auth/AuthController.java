package com.botfunnel.auth;

import com.botfunnel.auth.dto.AuthResponse;
import com.botfunnel.auth.dto.ForgotPasswordRequest;
import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.auth.dto.MeResponse;
import com.botfunnel.auth.dto.RegisterRequest;
import com.botfunnel.auth.dto.RegisterResponse;
import com.botfunnel.auth.dto.ResendVerificationRequest;
import com.botfunnel.auth.dto.ResetPasswordRequest;
import com.botfunnel.auth.dto.VerifyEmailResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                    ServerWebExchange exchange) {
        return authService.login(request, exchange).map(ResponseEntity::ok);
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<MeResponse>> me() {
        return authService.me().map(ResponseEntity::ok);
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request,
                                                           ServerWebExchange exchange) {
        return authService.register(request, exchange)
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @GetMapping("/verify-email")
    public Mono<ResponseEntity<VerifyEmailResponse>> verifyEmail(@RequestParam("token") String token,
                                                                 ServerWebExchange exchange) {
        return authService.verifyEmail(token, exchange).map(ResponseEntity::ok);
    }

    @PostMapping("/resend-verification")
    public Mono<ResponseEntity<Void>> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        return authService.resendVerification(request.getEmail())
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        return authService.logout(exchange)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/forgot-password")
    public Mono<ResponseEntity<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                                     ServerWebExchange exchange) {
        return authService.forgotPassword(request.getEmail(), exchange)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/reset-password")
    public Mono<ResponseEntity<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                                    ServerWebExchange exchange) {
        return authService.resetPassword(request.getToken(), request.getNewPassword(), exchange)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }
}
