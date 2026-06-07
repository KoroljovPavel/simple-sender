package com.botfunnel.funnel.dto;

// Preview render result (Decision 9): EXACTLY {rendered, sampleData, kind} — no ownerChatId / identity
// fields leak to the client. {@code rendered} is the escaped output; {@code sampleData} is true when the
// render ran against a stub subscriber (owner not linked) rather than the real owner; {@code kind} is
// "message" for SEND_MESSAGE/SEND_IMAGE/MENU and "non_message" for the action steps (DELAY/ADD_TAG/...).
public record PreviewStepResponse(String rendered, boolean sampleData, String kind) {}
