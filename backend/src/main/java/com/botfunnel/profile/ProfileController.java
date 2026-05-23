package com.botfunnel.profile;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.common.HttpRequestUtils;
import com.botfunnel.profile.dto.ChangePasswordRequest;
import com.botfunnel.profile.dto.ProfileResponse;
import com.botfunnel.profile.dto.UpdateProfileRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile() {
        return ResponseEntity.ok(profileService.getProfile(currentUserId()));
    }

    @PatchMapping
    public ResponseEntity<ProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(profileService.updateProfile(currentUserId(), request));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpSession session) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        profileService.changePassword(currentUserId(), request.getCurrentPassword(),
                request.getNewPassword(), session, ip, userAgent);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/terminate-all-sessions")
    public ResponseEntity<Void> terminateAllSessions() {
        profileService.terminateAllSessions(currentUserId());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(HttpServletRequest httpRequest, HttpSession session) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        profileService.deleteAccount(currentUserId(), session, ip, userAgent);
        return ResponseEntity.ok().build();
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
