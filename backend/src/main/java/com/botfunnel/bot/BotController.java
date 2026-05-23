package com.botfunnel.bot;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.bot.dto.BotResponse;
import com.botfunnel.bot.dto.ConnectBotRequest;
import com.botfunnel.common.AppException;
import com.botfunnel.common.HttpRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/bot")
public class BotController {

    private final BotService botService;

    public BotController(BotService botService) {
        this.botService = botService;
    }

    @GetMapping
    public ResponseEntity<BotResponse> get(@PathVariable String projectId) {
        String ownerId = currentUserId();
        Bot bot = botService.getByProject(ownerId, projectId);
        return ResponseEntity.ok(toResponse(bot));
    }

    @PostMapping("/connect")
    public ResponseEntity<BotResponse> connect(@PathVariable String projectId,
                                               @Valid @RequestBody ConnectBotRequest request,
                                               HttpServletRequest httpRequest) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        String ownerId = currentUserId();
        Bot bot = botService.connect(ownerId, projectId, request.token(), ip, userAgent);
        return ResponseEntity.ok(toResponse(bot));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Void> disconnect(@PathVariable String projectId,
                                           HttpServletRequest httpRequest) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        String ownerId = currentUserId();
        botService.disconnect(ownerId, projectId, ip, userAgent);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/test-message")
    public ResponseEntity<Void> testMessage(@PathVariable String projectId,
                                            HttpServletRequest httpRequest) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        String ownerId = currentUserId();
        botService.sendTestMessage(ownerId, projectId, ip, userAgent);
        return ResponseEntity.ok().build();
    }

    private static BotResponse toResponse(Bot bot) {
        return new BotResponse(
                bot.getTelegramBotId(),
                bot.getTelegramUsername(),
                bot.getTelegramFirstName(),
                bot.getTokenSuffix(),
                bot.getStatus(),
                bot.getConnectedAt());
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
