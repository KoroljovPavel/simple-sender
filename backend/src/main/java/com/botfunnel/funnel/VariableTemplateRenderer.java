package com.botfunnel.funnel;

import com.botfunnel.subscriber.Subscriber;

import java.time.Instant;
import java.util.Map;

/**
 * Pure static utility that substitutes variable placeholders in funnel step texts (SendMessage
 * {@code text} / SendImage {@code caption}) before they are sent to Telegram. The funnel execution
 * engine (Task 6) runs every outgoing text/caption through {@link #render} first.
 *
 * <p>Supported placeholders:
 * <ul>
 *   <li>{@code {user.first_name}} / {@code {user.last_name}} / {@code {user.username}} — subscriber
 *       fields.</li>
 *   <li>{@code {custom.<key>}} — the subscriber's custom-field value for {@code <key>}.</li>
 * </ul>
 *
 * <p>Rules:
 * <ul>
 *   <li>An unknown placeholder (no such {@code user.*} field / {@code custom.<key>}) or a
 *       present-but-null/empty value renders as the empty string — the placeholder simply
 *       disappears, never an error.</li>
 *   <li>{@code {{} escapes to a literal {@code {} and {@code }}} to a literal {@code }}; an escaped
 *       brace is NOT treated as the start/end of a placeholder.</li>
 *   <li>An unclosed {@code {} (no matching {@code }}) is left verbatim — the renderer never throws.</li>
 *   <li>Single pass: a substituted value that itself contains {@code {}/{@code }} is NOT re-parsed.</li>
 *   <li>Human rendering of non-string custom values: integral {@code Double} drops the {@code .0}
 *       ({@code 30.0} → {@code 30}); {@code Boolean} → {@code true}/{@code false}; {@code Instant} →
 *       ISO-8601 (UTC); anything else → {@code String.valueOf}.</li>
 *   <li>Injection defence (Decision 10 / OWASP A03): ONLY the substituted values are escaped per the
 *       chosen {@code parseMode}; author markup in the template is left untouched.
 *       <ul>
 *         <li>{@code None} ({@code null} parseMode) — no escaping (plain text, the safest mode).</li>
 *         <li>{@code HTML} — escape {@code &}, {@code <}, {@code >} in substituted values.</li>
 *         <li>{@code MarkdownV2} — escape the full official Telegram MarkdownV2 special-char set in
 *             substituted values.</li>
 *       </ul></li>
 * </ul>
 *
 * <p>No I/O, no Spring deps, no logging of rendered values (PII, Decision 16).
 */
public final class VariableTemplateRenderer {

    private static final String USER_PREFIX = "user.";
    private static final String CUSTOM_PREFIX = "custom.";

    /**
     * Full official Telegram MarkdownV2 special-char set (per Telegram Bot API "Formatting options"):
     * {@code _ * [ ] ( ) ~ ` > # + - = | { } . !}. Each must be escaped with a preceding backslash
     * when it appears inside user-supplied text.
     */
    private static final String MARKDOWN_V2_SPECIALS = "_*[]()~`>#+-=|{}.!";

    private VariableTemplateRenderer() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Renders {@code template} against the {@code subscriber} context, escaping substituted values
     * per {@code parseMode} ({@code null} = None | {@code "HTML"} | {@code "MarkdownV2"}).
     */
    public static String render(String template, String parseMode, Subscriber subscriber) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        int len = template.length();
        StringBuilder out = new StringBuilder(len);

        int i = 0;
        while (i < len) {
            char c = template.charAt(i);

            // Escaped braces: {{ -> {  and  }} -> }  (take precedence over substitution).
            if (c == '{' && i + 1 < len && template.charAt(i + 1) == '{') {
                out.append('{');
                i += 2;
                continue;
            }
            if (c == '}' && i + 1 < len && template.charAt(i + 1) == '}') {
                out.append('}');
                i += 2;
                continue;
            }

            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close == -1) {
                    // Unclosed brace — emit the rest verbatim, never throw.
                    out.append(template, i, len);
                    break;
                }
                String key = template.substring(i + 1, close);
                String value = resolve(key, subscriber);
                if (!value.isEmpty()) {
                    out.append(escape(value, parseMode));
                }
                i = close + 1;
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    /**
     * Resolves a placeholder key to its raw rendered value, or {@code ""} for unknown keys and
     * null/empty values. Does not escape — escaping happens in {@link #escape}.
     */
    private static String resolve(String key, Subscriber subscriber) {
        if (key.startsWith(USER_PREFIX)) {
            return renderValue(userField(key.substring(USER_PREFIX.length()), subscriber));
        }
        if (key.startsWith(CUSTOM_PREFIX)) {
            Map<String, Object> customFields = subscriber.getCustomFields();
            if (customFields == null) {
                return "";
            }
            return renderValue(customFields.get(key.substring(CUSTOM_PREFIX.length())));
        }
        return "";
    }

    private static Object userField(String field, Subscriber subscriber) {
        return switch (field) {
            case "first_name" -> subscriber.getFirstName();
            case "last_name" -> subscriber.getLastName();
            case "username" -> subscriber.getUsername();
            default -> null;
        };
    }

    /**
     * Human-readable rendering of a raw value. {@code null} → {@code ""}; integral {@code Double}
     * drops the {@code .0}; {@code Boolean}/{@code Instant} via their canonical {@code toString};
     * everything else via {@link String#valueOf}.
     */
    private static String renderValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            if (d == Math.floor(d) && !d.isInfinite()) {
                return Long.toString(d.longValue());
            }
            return d.toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString(); // ISO-8601 UTC
        }
        return String.valueOf(value);
    }

    /** Escapes a substituted value per parseMode. {@code None}/null → unchanged. */
    private static String escape(String value, String parseMode) {
        if (parseMode == null) {
            return value; // None
        }
        return switch (parseMode) {
            case "HTML" -> escapeHtml(value);
            case "MarkdownV2" -> escapeMarkdownV2(value);
            default -> value; // unknown mode → treat as plain text, no escaping
        };
    }

    private static String escapeHtml(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeMarkdownV2(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (MARKDOWN_V2_SPECIALS.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
