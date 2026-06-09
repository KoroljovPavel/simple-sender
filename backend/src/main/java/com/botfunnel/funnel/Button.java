package com.botfunnel.funnel;

/**
 * Immutable inline-keyboard button on the last non-album block of a {@link StepType#MESSAGE} composer step
 * (15-message-composer / Decision 2). Persisted by Spring Data as a nested document inside
 * {@link FunnelStep#getButtons()} (flat POJO, no {@code _class} discriminator — Decision 12). Being a
 * record, it is deeply immutable, so {@link FunnelStep#copyOf} only needs a defensive copy of the enclosing
 * list, not of the elements.
 *
 * <p>No bean-validation here — graph/button validation lives in the DTO/service layer (Task 4).
 *
 * @param type         {@code "callback"} | {@code "url"}
 * @param label        button caption (plain text — Telegram does not render markdown in buttons)
 * @param targetStepId for a {@code callback} button: the target step {@code id}, or {@code null} = End
 * @param url          for a {@code url} button: the {@code http(s)} link
 */
public record Button(String type, String label, String targetStepId, String url) {
}
