package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.CanvasPositionDto;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
import com.botfunnel.funnel.dto.NoteDto;
import com.botfunnel.funnel.dto.TriggerDto;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 18-funnel-canvas / Task 1 (Decision 7): the SERVICE-SIDE notes guards through {@link FunnelService}.
 * The DTO {@code @Size} only fires at the controller (@Valid); calling {@code update} directly bypasses it,
 * so this test proves the service re-check (the second line of defense, mirroring the MAX_TRIGGERS guard)
 * actually fires: array-size cap, per-note text length, and server-minted note id.
 */
class FunnelServiceNotesTest extends AbstractIntegrationTest {

    private static final String USER_ID = "fn-notes-user";

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
        u.setEmail("fn-notes@test.com");
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

    private static TriggerDto onStart(String value) {
        return new TriggerDto("on_start", value, null, null, null);
    }

    private UpdateFunnelRequest reqWithNotes(List<NoteDto> notes) {
        return new UpdateFunnelRequest("f", null, false, List.of(onStart("")), List.of(), notes);
    }

    private void assert422(List<NoteDto> notes, String expectedCode) {
        String id = createDraft();
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, reqWithNotes(notes)))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(expectedCode);
                });
    }

    @Test
    void notes_arraySizeCapReChecked_serviceSide() {
        // 51 notes exceeds MAX_NOTES=50 — the service re-check fires (the DTO @Size is bypassed here).
        List<NoteDto> tooMany = IntStream.range(0, 51)
                .mapToObj(i -> new NoteDto(null, "n" + i, null))
                .toList();
        assert422(tooMany, FunnelService.CODE_NOTE_LIMIT);
    }

    @Test
    void notes_textLengthReChecked_serviceSide() {
        List<NoteDto> bigText = List.of(new NoteDto(null, "x".repeat(NoteDto.NOTE_TEXT_MAX + 1), null));
        assert422(bigText, FunnelService.CODE_NOTE_INVALID);
    }

    @Test
    void notes_arraySizeCapBoundaryAccepted() {
        // Exactly MAX_NOTES=50 is the inclusive-valid boundary — accepted.
        String id = createDraft();
        List<NoteDto> atCap = IntStream.range(0, 50)
                .mapToObj(i -> new NoteDto(null, "n" + i, null))
                .toList();
        funnelService.update(USER_ID, projectId, id, reqWithNotes(atCap));
        assertThat(funnelRepository.findById(id).orElseThrow().getNotes()).hasSize(50);
    }

    @Test
    void notes_idServerMinted_whenNull_preserved_whenPresent() {
        String id = createDraft();

        // Null id → server-minted ObjectId hex.
        funnelService.update(USER_ID, projectId, id,
                reqWithNotes(List.of(new NoteDto(null, "fresh", new CanvasPositionDto(1.0, 2.0)))));
        List<Note> minted = funnelRepository.findById(id).orElseThrow().getNotes();
        assertThat(minted).hasSize(1);
        String mintedId = minted.get(0).id();
        assertThat(mintedId).matches("[0-9a-f]{24}");
        assertThat(minted.get(0).text()).isEqualTo("fresh");
        assertThat(minted.get(0).canvasPosition()).isEqualTo(new CanvasPosition(1.0, 2.0));

        // Echoed non-null id → preserved (not re-minted).
        funnelService.update(USER_ID, projectId, id,
                reqWithNotes(List.of(new NoteDto(mintedId, "fresh", new CanvasPositionDto(1.0, 2.0)))));
        assertThat(funnelRepository.findById(id).orElseThrow().getNotes().get(0).id()).isEqualTo(mintedId);
    }

    @Test
    void notes_nullRequest_clearsNotes() {
        String id = createDraft();
        // First set a note.
        funnelService.update(USER_ID, projectId, id, reqWithNotes(List.of(new NoteDto(null, "n", null))));
        assertThat(funnelRepository.findById(id).orElseThrow().getNotes()).hasSize(1);

        // A null notes request clears them (additive-nullable; full-replace semantics).
        funnelService.update(USER_ID, projectId, id, reqWithNotes(null));
        assertThat(funnelRepository.findById(id).orElseThrow().getNotes()).isNull();
    }
}
