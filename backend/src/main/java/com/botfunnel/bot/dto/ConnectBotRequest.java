package com.botfunnel.bot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// @JsonIgnoreProperties(ignoreUnknown = true) silently discards any unknown fields posted by the
// client (defense against mass assignment, identical to CreateProjectRequest). The @Pattern regex
// (D16) short-circuits malformed tokens at the bean validator with a 400 from GlobalErrorHandler
// so BotService.connect is never invoked and no Telegram call ever fires for a malformed token (AC2).
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectBotRequest(
        @NotBlank
        @Pattern(regexp = "^\\d{1,20}:[A-Za-z0-9_-]{30,50}$")
        String token
) {}
