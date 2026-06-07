package com.botfunnel.funnel;

/**
 * Contract consumed by the webhook worker (ProcessTelegramUpdateJob) for funnel side effects.
 * The real implementation is deferred to Epic 06; Epic 04b ships a no-op @Service so the
 * worker call sites compile and integration tests run without firing real funnels.
 */
public interface FunnelTriggerService {

    void fire(String projectId, Long chatId, String triggerType, String payload);

    void cancelActiveFor(String projectId, Long chatId);

    /**
     * Callback-button entry point (Phase 2): the last server hop of the callback path. Called by the
     * webhook worker (Task 7) when a subscriber taps an inline button on a {@code MENU} step. Resolves
     * the project's CONNECTED bot → subscriber → parked execution, strictly parses the
     * {@code callback_data} {@code "{executionId}:{buttonIndex}"}, authorizes the execution's owner
     * (anti-IDOR, Decision 6), and — only when the button is an in-range callback button on the
     * subscriber's own {@code waiting_for_reply} {@code MENU} — delegates to
     * {@code FunnelExecutionEngine.resumeOnCallback} to advance the chosen branch.
     *
     * <p><strong>Contract:</strong>
     * <ul>
     *   <li><strong>error-isolated</strong> — the entire body is wrapped in a {@code try/catch(Throwable)}
     *       that swallows + logs; this method NEVER throws outward (a callback fault must not poison the
     *       webhook dispatch), exactly like {@code fire()} / {@code cancelActiveFor()}.</li>
     *   <li><strong>anti-IDOR</strong> (Decision 6) — {@code exec.subscriberId} and {@code exec.projectId}
     *       must match the resolved subscriber / project, else a silent no-op. A forged {@code callback_data}
     *       pointing at another subscriber's execution never advances it (also CAS-scoped by subscriberId
     *       in {@code resumeOnCallback}).</li>
     *   <li><strong>answerCallbackQuery ALWAYS</strong> (Decision 8) — best-effort, in its own
     *       try/catch, on EVERY exit path (success, stale, malformed, oversized, foreign, URL-button,
     *       null-lookup) whenever the bot (hence {@code botId}) is known, so the subscriber's spinner is
     *       guaranteed to clear even when no advance happens. Its failure (Telegram 5xx) never blocks the
     *       branch advance — WARN-log, no audit event.</li>
     *   <li><strong>no-PII event</strong> (Decision 9) — on a successful claim only, writes
     *       {@code funnel_button_clicked} via {@code EventService} (ids/codes only — no
     *       {@code from.first_name} / username / text) and sets {@code lastButtonClicked} on the execution.</li>
     * </ul>
     */
    void advanceOnCallback(String projectId, Long chatId, String callbackData, String callbackQueryId);
}
