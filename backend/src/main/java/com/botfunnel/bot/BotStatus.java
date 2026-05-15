package com.botfunnel.bot;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// HTTP wire form is lowercase ("connected"/"disconnected") per OpenAPI contract.
// Spring Data MongoDB persists the enum as its name() ("CONNECTED"/"DISCONNECTED"),
// which is what the partial-unique index filter expression { status: "CONNECTED" } matches.
public enum BotStatus {
    CONNECTED,
    DISCONNECTED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static BotStatus fromString(String s) {
        return BotStatus.valueOf(s.toUpperCase());
    }
}
