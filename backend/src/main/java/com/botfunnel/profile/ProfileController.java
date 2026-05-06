package com.botfunnel.profile;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.profile.dto.ChangePasswordRequest;
import com.botfunnel.profile.dto.ProfileResponse;
import com.botfunnel.profile.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final int USER_AGENT_MAX = 500;

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public Mono<ResponseEntity<ProfileResponse>> getProfile() {
        return currentUserId().flatMap(profileService::getProfile).map(ResponseEntity::ok);
    }

    @PatchMapping
    public Mono<ResponseEntity<ProfileResponse>> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return currentUserId()
                .flatMap(userId -> profileService.updateProfile(userId, request))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/change-password")
    public Mono<ResponseEntity<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                     ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(userId -> exchange.getSession().flatMap(session -> profileService.changePassword(
                        userId, request.getCurrentPassword(), request.getNewPassword(),
                        session, ip, userAgent)))
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/terminate-all-sessions")
    public Mono<ResponseEntity<Void>> terminateAllSessions() {
        return currentUserId()
                .flatMap(profileService::terminateAllSessions)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @DeleteMapping
    public Mono<ResponseEntity<Void>> deleteAccount(ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(userId -> exchange.getSession().flatMap(session ->
                        profileService.deleteAccount(userId, session, ip, userAgent)))
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    private static Mono<String> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof AppUserDetails)
                .map(a -> ((AppUserDetails) a.getPrincipal()).id())
                .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")));
    }

    private static String capUserAgent(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() > USER_AGENT_MAX ? userAgent.substring(0, USER_AGENT_MAX) : userAgent;
    }

    private static String extractIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }
}
