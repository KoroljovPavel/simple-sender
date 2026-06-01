package com.botfunnel.funnel;

import com.botfunnel.subscriber.Subscriber;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableTemplateRendererTest {

    private static Subscriber subscriber(String firstName, String lastName, String username,
                                         Map<String, Object> customFields) {
        Subscriber s = new Subscriber();
        s.setFirstName(firstName);
        s.setLastName(lastName);
        s.setUsername(username);
        s.setCustomFields(customFields);
        return s;
    }

    @Test
    void substitutesUserFields() {
        Subscriber s = subscriber("Ada", "Lovelace", "ada", null);

        String result = VariableTemplateRenderer.render(
                "Hi {user.first_name} {user.last_name} (@{user.username})", null, s);

        assertThat(result).isEqualTo("Hi Ada Lovelace (@ada)");
    }

    @Test
    void substitutesCustomField() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("city", "Kyiv");
        Subscriber s = subscriber("Ada", null, null, cf);

        String result = VariableTemplateRenderer.render("Your city: {custom.city}", null, s);

        assertThat(result).isEqualTo("Your city: Kyiv");
    }

    @Test
    void unknownPlaceholderRendersEmpty() {
        Subscriber s = subscriber("Ada", null, null, Map.of());

        String result = VariableTemplateRenderer.render(
                "[{user.middle_name}][{custom.missing}]", null, s);

        assertThat(result).isEqualTo("[][]");
    }

    @Test
    void emptyOrNullValueRendersEmpty() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("empty", "");
        cf.put("nil", null);
        // first_name + last_name present but null, username present but empty
        Subscriber s = subscriber(null, null, "", cf);

        // {user.last_name} pins the null user-field resolution branch.
        String result = VariableTemplateRenderer.render(
                "[{user.first_name}][{user.last_name}][{user.username}][{custom.empty}][{custom.nil}]",
                null, s);

        assertThat(result).isEqualTo("[][][][][]");
    }

    @Test
    void escapedBracesAreLiteral() {
        Subscriber s = subscriber("Ada", null, null, null);

        String result = VariableTemplateRenderer.render(
                "literal {{user.first_name}} and {{ and }}", null, s);

        assertThat(result).isEqualTo("literal {user.first_name} and { and }");
    }

    @Test
    void rendersDoubleWithoutTrailingZero() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("age", 30.0d);
        cf.put("price", 30.5d);
        Subscriber s = subscriber(null, null, null, cf);

        String result = VariableTemplateRenderer.render(
                "{custom.age} / {custom.price}", null, s);

        assertThat(result).isEqualTo("30 / 30.5");
    }

    @Test
    void rendersBooleanHumanReadable() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("optedIn", Boolean.TRUE);
        cf.put("optedOut", Boolean.FALSE);
        Subscriber s = subscriber(null, null, null, cf);

        String result = VariableTemplateRenderer.render(
                "{custom.optedIn}/{custom.optedOut}", null, s);

        // Canonical lowercase true/false is intentional — this is a pure util with no i18n.
        assertThat(result).isEqualTo("true/false");
    }

    @Test
    void rendersInstantAsIso() {
        Instant instant = Instant.parse("2026-06-01T10:15:30Z");
        Map<String, Object> cf = new HashMap<>();
        cf.put("joinedAt", instant);
        Subscriber s = subscriber(null, null, null, cf);

        String result = VariableTemplateRenderer.render("{custom.joinedAt}", null, s);

        assertThat(result).isEqualTo("2026-06-01T10:15:30Z");
    }

    @Test
    void htmlModeEscapesSubstitutedValues() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("note", "a & b < c > d");
        // first_name carries an HTML-injection attempt
        Subscriber s = subscriber("<b>Ada</b>", null, null, cf);

        // Author markup (<b>...</b>) in the template must survive untouched; only the substituted
        // values get their &, <, > escaped.
        String result = VariableTemplateRenderer.render(
                "<b>{user.first_name}</b>: {custom.note}", "HTML", s);

        assertThat(result).isEqualTo("<b>&lt;b&gt;Ada&lt;/b&gt;</b>: a &amp; b &lt; c &gt; d");
    }

    @Test
    void htmlModeEscapesDoubleQuoteInAttribute() {
        Map<String, Object> cf = new HashMap<>();
        // Value injected into an HTML attribute tries to break out of the quoted attribute via ".
        cf.put("url", "x\" onmouseover=\"evil()");
        Subscriber s = subscriber(null, null, null, cf);

        // Author attribute markup (href="...") survives; only the substituted value's " is escaped
        // to &quot; (regression for the missing double-quote escaping).
        String result = VariableTemplateRenderer.render(
                "<a href=\"{custom.url}\">link</a>", "HTML", s);

        assertThat(result).isEqualTo(
                "<a href=\"x&quot; onmouseover=&quot;evil()\">link</a>");
    }

    @Test
    void markdownV2ModeEscapesFullCharset() {
        Map<String, Object> cf = new HashMap<>();
        // Every official MarkdownV2 special char in one value, plus a leading backslash.
        cf.put("payload", "\\_*[]()~`>#+-=|{}.!");
        Subscriber s = subscriber(null, null, null, cf);

        // Author markup (*bold*) in the template stays intact; only the value is escaped.
        String result = VariableTemplateRenderer.render(
                "*bold* {custom.payload}", "MarkdownV2", s);

        // The leading '\' is escaped to '\\' (regression for the backslash-injection fix).
        assertThat(result).isEqualTo(
                "*bold* \\\\\\_\\*\\[\\]\\(\\)\\~\\`\\>\\#\\+\\-\\=\\|\\{\\}\\.\\!");
    }

    @Test
    void markdownV2EscapesBackslashToPreventInjection() {
        Map<String, Object> cf = new HashMap<>();
        // A naive escaper that only escapes the '*' would turn "\*" into "\\*": Telegram then
        // renders a literal '\' followed by an ACTIVE '*' (markup injection). The backslash itself
        // must be escaped so the result is "\\\\\\*" -> literal '\' + literal '*'.
        cf.put("payload", "\\*");
        Subscriber s = subscriber(null, null, null, cf);

        String result = VariableTemplateRenderer.render("{custom.payload}", "MarkdownV2", s);

        assertThat(result).isEqualTo("\\\\\\*");
    }

    @Test
    void noneModeDoesNotEscape() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("note", "a & b < c > * _ [");
        Subscriber s = subscriber(null, null, null, cf);

        // parseMode null = None → no escaping at all.
        String result = VariableTemplateRenderer.render("{custom.note}", null, s);

        assertThat(result).isEqualTo("a & b < c > * _ [");
    }

    // ---- edge cases from Details ----

    @Test
    void unclosedBraceLeftAsIs() {
        Subscriber s = subscriber("Ada", null, null, null);

        String result = VariableTemplateRenderer.render("hello {user.first_name", null, s);

        assertThat(result).isEqualTo("hello {user.first_name");
    }

    @Test
    void substitutedValueContainingBracesIsNotReparsed() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("raw", "{user.first_name}");
        Subscriber s = subscriber("Ada", null, null, cf);

        // Single pass: the {user.first_name} that came FROM the value must not be substituted again.
        String result = VariableTemplateRenderer.render("{custom.raw}", null, s);

        assertThat(result).isEqualTo("{user.first_name}");
    }

    @Test
    void nullParseModeTreatedAsNone() {
        Map<String, Object> cf = new HashMap<>();
        cf.put("v", "<&>");
        Subscriber s = subscriber(null, null, null, cf);

        assertThat(VariableTemplateRenderer.render("{custom.v}", null, s)).isEqualTo("<&>");
    }

    @Test
    void nullTemplateRendersEmpty() {
        Subscriber s = subscriber("Ada", null, null, null);

        assertThat(VariableTemplateRenderer.render(null, null, s)).isEqualTo("");
    }

    @Test
    void nullCustomFieldsMapDoesNotThrow() {
        Subscriber s = subscriber("Ada", null, null, null);

        assertThat(VariableTemplateRenderer.render("{custom.any}", null, s)).isEqualTo("");
    }

    @Test
    void unknownUserSubFieldRendersEmpty() {
        Subscriber s = subscriber("Ada", null, null, null);

        // {user.something_unsupported} is not one of first_name/last_name/username.
        assertThat(VariableTemplateRenderer.render("[{user.nope}]", null, s)).isEqualTo("[]");
    }

    @Test
    void emptyPlaceholderRendersEmpty() {
        Subscriber s = subscriber("Ada", null, null, null);

        assertThat(VariableTemplateRenderer.render("[{}]", null, s)).isEqualTo("[]");
    }

    @Test
    void loneClosingBraceWithoutOpenerIsLiteral() {
        Subscriber s = subscriber("Ada", null, null, null);

        // A '}' not preceded by '{' and not part of '}}' is emitted verbatim.
        assertThat(VariableTemplateRenderer.render("foo } bar", null, s)).isEqualTo("foo } bar");
    }

    @Test
    void closingBracesAfterPlaceholderLeaveLoneBrace() {
        Subscriber s = subscriber("Ada", null, null, null);

        // Single-pass scan: "{user.first_name}}" binds the '{' to the FIRST '}' (placeholder close),
        // substitutes the value, then the leftover lone '}' is emitted verbatim.
        assertThat(VariableTemplateRenderer.render("{user.first_name}}", null, s))
                .isEqualTo("Ada}");
    }

    @Test
    void unknownNamespaceRendersEmpty() {
        Subscriber s = subscriber("Ada", null, null, null);

        assertThat(VariableTemplateRenderer.render("[{order.id}]", null, s)).isEqualTo("[]");
    }
}
