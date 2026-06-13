package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
import com.botfunnel.funnel.dto.FunnelResponse;
import com.botfunnel.funnel.dto.FunnelStepDto;
import com.botfunnel.funnel.dto.KeyboardButtonDto;
import com.botfunnel.funnel.dto.KeyboardRowDto;
import com.botfunnel.funnel.dto.UpdateFunnelRequest;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 16-persistent-keyboard / Decision 6: the {@code SET_KEYBOARD} and {@code CLEAR_KEYBOARD} steps carry a
 * mandatory text + optional reply-keyboard rows. {@link FunnelService} validates them in
 * {@code validateSteps} ({@code validateSetKeyboard}/{@code validateClearKeyboard}) and round-trips all
 * five new fields through the step DTO ({@code toSteps} / {@code toStepDto}).
 *
 * <p>Cloned from {@link FunnelServiceEmitEventTest}: same seed (user/project/funnel repos), the same
 * {@code assert422} idiom asserting {@link FunnelService#CODE_INVALID_STEP}, a {@code keyboardStep(...)}
 * builder instead of {@code emitStep}.
 */
class FunnelServiceKeyboardStepTest extends AbstractIntegrationTest {

    private static final String USER_ID = "fsk-user";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired FunnelService funnelService;

    private String projectId;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        funnelRepository.deleteAll();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("fsk@test.com");
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("P");
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();
    }

    private String createDraft() {
        return funnelService.create(USER_ID, projectId, new CreateFunnelRequest("f", null)).id();
    }

    // FunnelStepDto positional (16-persistent-keyboard appends the last five): (stepType, id, next,
    // buttons, timeoutValue, timeoutUnit, timeoutTargetStepId, blocks, delayValue, delayUnit, tagSlug,
    // customFieldKey, customFieldValue, eventName, targetFunnelId, targetEntryStepId, endParentAfter,
    // keyboardText, keyboardParseMode, keyboardRows, isPersistent, oneTimeKeyboard).
    private FunnelStepDto setKeyboardStep(String text, String parseMode, List<KeyboardRowDto> rows,
                                          Boolean isPersistent, Boolean oneTime) {
        return new FunnelStepDto(
                StepType.SET_KEYBOARD, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false,
                text, parseMode, rows, isPersistent, oneTime, null);
    }

    private FunnelStepDto clearKeyboardStep(String text, String parseMode, List<KeyboardRowDto> rows,
                                            Boolean isPersistent, Boolean oneTime) {
        return new FunnelStepDto(
                StepType.CLEAR_KEYBOARD, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false,
                text, parseMode, rows, isPersistent, oneTime, null);
    }

    private static KeyboardRowDto row(String... labels) {
        List<KeyboardButtonDto> buttons = new ArrayList<>();
        for (String label : labels) {
            buttons.add(new KeyboardButtonDto(label));
        }
        return new KeyboardRowDto(buttons);
    }

    private UpdateFunnelRequest reqWithStep(FunnelStepDto step) {
        // Phase 8 (17-funnel-multi-entry): a single bare on_start trigger replaces the former flat trio.
        return new UpdateFunnelRequest("f", null, false,
                List.of(new com.botfunnel.funnel.dto.TriggerDto("on_start", "", null, null, null)), List.of(step), null);
    }

    private void assert422(FunnelStepDto step) {
        String id = createDraft();
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, reqWithStep(step)))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(FunnelService.CODE_INVALID_STEP);
                });
    }

    // ─── valid round-trips ────────────────────────────────────────────────────────

    @Test
    void setKeyboard_valid_acceptedAndRoundTrips() {
        String id = createDraft();
        FunnelStepDto step = setKeyboardStep(
                "Меню {user.first_name}", "HTML",
                List.of(row("Згенерувати бонус", "Профіль"), row("Допомога")),
                true, false);

        FunnelResponse resp = funnelService.update(USER_ID, projectId, id, reqWithStep(step));

        assertThat(resp.steps()).hasSize(1);
        FunnelStepDto out = resp.steps().get(0);
        assertThat(out.stepType()).isEqualTo(StepType.SET_KEYBOARD);
        assertThat(out.keyboardText()).isEqualTo("Меню {user.first_name}");
        assertThat(out.keyboardParseMode()).isEqualTo("HTML");
        assertThat(out.isPersistent()).isTrue();
        assertThat(out.oneTimeKeyboard()).isFalse();
        assertThat(out.keyboardRows()).hasSize(2);
        assertThat(out.keyboardRows().get(0).buttons()).extracting(KeyboardButtonDto::text)
                .containsExactly("Згенерувати бонус", "Профіль");
        assertThat(out.keyboardRows().get(1).buttons()).extracting(KeyboardButtonDto::text)
                .containsExactly("Допомога");

        // ...and is persisted on the funnel step (toSteps wiring).
        FunnelStep persisted = funnelRepository.findById(id).orElseThrow().getSteps().get(0);
        assertThat(persisted.getKeyboardText()).isEqualTo("Меню {user.first_name}");
        assertThat(persisted.getKeyboardParseMode()).isEqualTo("HTML");
        assertThat(persisted.getIsPersistent()).isTrue();
        assertThat(persisted.getOneTimeKeyboard()).isFalse();
        assertThat(persisted.getKeyboardRows()).hasSize(2);
        assertThat(persisted.getKeyboardRows().get(0).buttons()).extracting(KeyboardButton::text)
                .containsExactly("Згенерувати бонус", "Профіль");
    }

    @Test
    void setKeyboard_nullBooleans_areValid() {
        // is_persistent / one_time_keyboard are form concerns (Task 2) / executor-defaulted (Task 3) —
        // null is valid on SET_KEYBOARD.
        String id = createDraft();
        FunnelStepDto step = setKeyboardStep("Меню", null, List.of(row("A")), null, null);

        FunnelResponse resp = funnelService.update(USER_ID, projectId, id, reqWithStep(step));

        FunnelStepDto out = resp.steps().get(0);
        assertThat(out.isPersistent()).isNull();
        assertThat(out.oneTimeKeyboard()).isNull();
        assertThat(out.keyboardParseMode()).isNull();
    }

    @Test
    void clearKeyboard_valid_acceptedAndRoundTrips() {
        String id = createDraft();
        FunnelStepDto step = clearKeyboardStep("Меню сховано", "MarkdownV2", null, null, null);

        FunnelResponse resp = funnelService.update(USER_ID, projectId, id, reqWithStep(step));

        assertThat(resp.steps()).hasSize(1);
        FunnelStepDto out = resp.steps().get(0);
        assertThat(out.stepType()).isEqualTo(StepType.CLEAR_KEYBOARD);
        assertThat(out.keyboardText()).isEqualTo("Меню сховано");
        assertThat(out.keyboardParseMode()).isEqualTo("MarkdownV2");
        assertThat(out.keyboardRows()).isNull();

        FunnelStep persisted = funnelRepository.findById(id).orElseThrow().getSteps().get(0);
        assertThat(persisted.getKeyboardText()).isEqualTo("Меню сховано");
        assertThat(persisted.getKeyboardRows()).isNull();
    }

    @Test
    void setKeyboard_textAtCap_isValid() {
        // text exactly 4096 is the inclusive boundary → valid (4097 is rejected by textOverCap test).
        String id = createDraft();
        FunnelStepDto step = setKeyboardStep("x".repeat(4096), null, List.of(row("A")), false, true);

        FunnelResponse resp = funnelService.update(USER_ID, projectId, id, reqWithStep(step));
        assertThat(resp.steps().get(0).keyboardText()).hasSize(4096);
    }

    @Test
    void setKeyboard_buttonTextAtCap_isValidAndRoundTrips() {
        // button text exactly 64 is the inclusive boundary → valid (65 is rejected by overCap test).
        // Assert the label round-trips so a mapper that dropped the boundary button would NOT stay green.
        String id = createDraft();
        FunnelStepDto step = setKeyboardStep("Меню", null, List.of(row("y".repeat(64))), false, true);

        FunnelResponse resp = funnelService.update(USER_ID, projectId, id, reqWithStep(step));
        assertThat(resp.steps().get(0).keyboardRows().get(0).buttons().get(0).text()).hasSize(64);
    }

    // ─── SET_KEYBOARD rejections ──────────────────────────────────────────────────

    @Test
    void setKeyboard_missingOrBlankText_rejected() {
        assert422(setKeyboardStep(null, null, List.of(row("A")), null, null));
        assert422(setKeyboardStep("   ", null, List.of(row("A")), null, null));
    }

    @Test
    void setKeyboard_textOverCap_rejected() {
        assert422(setKeyboardStep("x".repeat(4097), null, List.of(row("A")), null, null));
    }

    @Test
    void setKeyboard_invalidParseMode_rejected() {
        assert422(setKeyboardStep("Меню", "Markdown", List.of(row("A")), null, null));
        assert422(setKeyboardStep("Меню", "garbage", List.of(row("A")), null, null));
    }

    @Test
    void setKeyboard_noRows_rejected() {
        assert422(setKeyboardStep("Меню", null, null, null, null));
        assert422(setKeyboardStep("Меню", null, List.of(), null, null));
    }

    @Test
    void setKeyboard_tooManyRows_rejected() {
        List<KeyboardRowDto> rows = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            rows.add(row("b" + i));
        }
        assert422(setKeyboardStep("Меню", null, rows, null, null));
    }

    @Test
    void setKeyboard_rowWithNoButtons_rejected() {
        assert422(setKeyboardStep("Меню", null, List.of(new KeyboardRowDto(List.of())), null, null));
        assert422(setKeyboardStep("Меню", null, List.of(new KeyboardRowDto(null)), null, null));
    }

    @Test
    void setKeyboard_rowWithTooManyButtons_rejected() {
        assert422(setKeyboardStep("Меню", null, List.of(row("a", "b", "c", "d", "e")), null, null));
    }

    @Test
    void setKeyboard_blankButtonText_rejected() {
        assert422(setKeyboardStep("Меню", null, List.of(row("  ")), null, null));
        assert422(setKeyboardStep("Меню", null, List.of(row((String) null)), null, null));
    }

    @Test
    void setKeyboard_buttonTextOverCap_rejected() {
        assert422(setKeyboardStep("Меню", null, List.of(row("z".repeat(65))), null, null));
    }

    @Test
    void setKeyboard_duplicateTrimmedButtonTexts_rejected() {
        // Trimmed-equal labels across rows are duplicates (case is NOT folded — Decision 6).
        assert422(setKeyboardStep("Меню", null,
                List.of(row("Бонус"), row(" Бонус ")), null, null));
    }

    @Test
    void setKeyboard_nullRowElement_rejected() {
        List<KeyboardRowDto> rows = new ArrayList<>();
        rows.add(row("A"));
        rows.add(null);
        assert422(setKeyboardStep("Меню", null, rows, null, null));
    }

    @Test
    void setKeyboard_nullButtonElement_rejected() {
        List<KeyboardButtonDto> buttons = new ArrayList<>();
        buttons.add(new KeyboardButtonDto("A"));
        buttons.add(null);
        assert422(setKeyboardStep("Меню", null, List.of(new KeyboardRowDto(buttons)), null, null));
    }

    // ─── CLEAR_KEYBOARD rejections ────────────────────────────────────────────────

    @Test
    void clearKeyboard_missingOrBlankText_rejected() {
        assert422(clearKeyboardStep(null, null, null, null, null));
        assert422(clearKeyboardStep("   ", null, null, null, null));
    }

    @Test
    void clearKeyboard_textOverCap_rejected() {
        assert422(clearKeyboardStep("x".repeat(4097), null, null, null, null));
    }

    @Test
    void clearKeyboard_invalidParseMode_rejected() {
        assert422(clearKeyboardStep("Сховано", "Markdown", null, null, null));
    }

    @Test
    void clearKeyboard_withKeyboardRowsPresent_rejected() {
        // Even an EMPTY (non-null) rows list counts as "present" → 422 (symmetric strict rejection).
        assert422(clearKeyboardStep("Сховано", null, List.of(row("A")), null, null));
        assert422(clearKeyboardStep("Сховано", null, List.of(), null, null));
    }

    @Test
    void clearKeyboard_withIsPersistentPresent_rejected() {
        assert422(clearKeyboardStep("Сховано", null, null, true, null));
        assert422(clearKeyboardStep("Сховано", null, null, false, null));
    }

    @Test
    void clearKeyboard_withOneTimeKeyboardPresent_rejected() {
        assert422(clearKeyboardStep("Сховано", null, null, null, true));
        assert422(clearKeyboardStep("Сховано", null, null, null, false));
    }
}
