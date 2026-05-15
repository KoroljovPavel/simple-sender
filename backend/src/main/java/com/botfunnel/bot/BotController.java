package com.botfunnel.bot;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.bot.dto.BotResponse;
import com.botfunnel.bot.dto.ConnectBotRequest;
import com.botfunnel.common.AppException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/bot")
public class BotController {

    private static final int USER_AGENT_MAX = 500;

    private final BotService botService;

    public BotController(BotService botService) {
        this.botService = botService;
    }

    @GetMapping
    public Mono<ResponseEntity<BotResponse>> get(@PathVariable String projectId) {
        return currentUserId()
                .flatMap(ownerId -> botService.getByProject(ownerId, projectId))
                .map(bot -> ResponseEntity.ok(toResponse(bot)));
    }

    @PostMapping("/connect")
    public Mono<ResponseEntity<BotResponse>> connect(@PathVariable String projectId,
                                                     @Valid @RequestBody ConnectBotRequest request,
                                                     ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(ownerId -> botService.connect(ownerId, projectId, request.token(), ip, userAgent))
                .map(bot -> ResponseEntity.ok(toResponse(bot)));
    }

    @PostMapping("/disconnect")
    public Mono<ResponseEntity<Void>> disconnect(@PathVariable String projectId,
                                                 ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(ownerId -> botService.disconnect(ownerId, projectId, ip, userAgent))
                .then(Mono.fromCallable(() -> ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/test-message")
    public Mono<ResponseEntity<Void>> testMessage(@PathVariable String projectId,
                                                  ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(ownerId -> botService.sendTestMessage(ownerId, projectId, ip, userAgent))
                .then(Mono.fromCallable(() -> ResponseEntity.ok().<Void>build()));
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

    // Copied verbatim from ProfileController. A shared util is deferred (no other consumer yet).
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
