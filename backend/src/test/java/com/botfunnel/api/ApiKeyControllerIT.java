package com.botfunnel.api;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "apikey-user-fixed-id";
    private static final String OTHER_USER_ID = "apikey-other-user-id";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ApiKeyRepository apiKeyRepository;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        apiKeyRepository.deleteAll();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("apikey@test.com");
        u.setName("Alice");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    private Project saveProject(String ownerId, String name) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p);
    }

    private Project saveSoftDeleted(String ownerId, String name) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(Instant.now());
        return projectRepository.save(p);
    }

    // ---------- POST generate ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void generate_returnsPlaintextOnce() throws Exception {
        Project p = saveProject(USER_ID, "Acme");

        String body = mockMvc.perform(post("/api/v1/projects/" + p.getId() + "/api-key").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.mask").value(org.hamcrest.Matchers.endsWith("•••")))
                .andReturn().getResponse().getContentAsString();

        String plaintext = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey");
        assertThat(plaintext).isNotBlank();

        // DB stores ONLY hash + prefix; the full plaintext is never persisted.
        ApiKey stored = apiKeyRepository.findByProjectId(p.getId()).orElseThrow();
        assertThat(stored.getKeyHash()).isNotBlank();
        assertThat(stored.getKeyPrefix()).isNotBlank();
        assertThat(stored.getKeyHash()).isNotEqualTo(plaintext);
        assertThat(stored.getKeyPrefix()).isNotEqualTo(plaintext);
        // The hash is the SHA-256 of the plaintext (hash-only at rest).
        assertThat(stored.getKeyHash())
                .isEqualTo(com.botfunnel.common.crypto.Sha256Hex.hex(plaintext));
        // The plaintext head is exposed only as the short prefix (mask), never the full key.
        assertThat(plaintext).startsWith(stored.getKeyPrefix());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void regenerate_overwritesAndInvalidatesOld() throws Exception {
        Project p = saveProject(USER_ID, "Acme");

        String first = mockMvc.perform(post("/api/v1/projects/" + p.getId() + "/api-key").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstPlaintext = com.jayway.jsonpath.JsonPath.read(first, "$.apiKey");
        String firstHash = apiKeyRepository.findByProjectId(p.getId()).orElseThrow().getKeyHash();

        String second = mockMvc.perform(post("/api/v1/projects/" + p.getId() + "/api-key").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondPlaintext = com.jayway.jsonpath.JsonPath.read(second, "$.apiKey");

        assertThat(secondPlaintext).isNotEqualTo(firstPlaintext);

        // Regenerate = overwrite: exactly one row, old hash gone, new hash present.
        assertThat(apiKeyRepository.findAll()).hasSize(1);
        String currentHash = apiKeyRepository.findByProjectId(p.getId()).orElseThrow().getKeyHash();
        assertThat(currentHash).isNotEqualTo(firstHash);
        assertThat(apiKeyRepository.findByKeyHash(firstHash)).isEmpty();
        // The old plaintext no longer resolves; the new one does.
        assertThat(apiKeyRepository.findByKeyHash(
                com.botfunnel.common.crypto.Sha256Hex.hex(firstPlaintext))).isEmpty();
        assertThat(apiKeyRepository.findByKeyHash(
                com.botfunnel.common.crypto.Sha256Hex.hex(secondPlaintext))).isPresent();
    }

    // ---------- GET mask ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getAfterGenerate_returnsMaskedNoPlaintext() throws Exception {
        Project p = saveProject(USER_ID, "Acme");

        String genBody = mockMvc.perform(post("/api/v1/projects/" + p.getId() + "/api-key").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String plaintext = com.jayway.jsonpath.JsonPath.read(genBody, "$.apiKey");
        String prefix = apiKeyRepository.findByProjectId(p.getId()).orElseThrow().getKeyPrefix();

        String getBody = mockMvc.perform(get("/api/v1/projects/" + p.getId() + "/api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true))
                .andExpect(jsonPath("$.mask").value(prefix + "•••"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // The GET body must NOT leak the full plaintext anywhere.
        assertThat(getBody).doesNotContain(plaintext);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getWhenNoKey_returnsAbsent() throws Exception {
        Project p = saveProject(USER_ID, "Acme");

        mockMvc.perform(get("/api/v1/projects/" + p.getId() + "/api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false))
                .andExpect(jsonPath("$.mask").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------- ownership (anti-enumeration, uniform 404) ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void foreignProject_returns404Uniform_andDoesNotCreateKey() throws Exception {
        Project foreign = saveProject(OTHER_USER_ID, "Foreign");

        mockMvc.perform(post("/api/v1/projects/" + foreign.getId() + "/api-key").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/" + foreign.getId() + "/api-key"))
                .andExpect(status().isNotFound());

        // The foreign project's key must NOT have been created or exposed.
        assertThat(apiKeyRepository.findByProjectId(foreign.getId())).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void missingProject_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/projects/507f1f77bcf86cd799439011/api-key").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/507f1f77bcf86cd799439011/api-key"))
                .andExpect(status().isNotFound());
        assertThat(apiKeyRepository.findAll()).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void malformedProjectId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/projects/zzz-not-an-objectid/api-key").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/zzz-not-an-objectid/api-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void softDeletedOwnProject_returns404() throws Exception {
        Project deleted = saveSoftDeleted(USER_ID, "Gone");

        mockMvc.perform(post("/api/v1/projects/" + deleted.getId() + "/api-key").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/" + deleted.getId() + "/api-key"))
                .andExpect(status().isNotFound());
        assertThat(apiKeyRepository.findByProjectId(deleted.getId())).isEmpty();
    }

    // ---------- unauthenticated (session chain) ----------

    @Test
    void unauthenticated_returns403() throws Exception {
        // Session chain (@Order(2)): anonymous on an authenticated() /api/** route → AuthorizationFilter
        // → 403 (matches ProjectControllerIT.anyEndpoint_unauthenticated_returns403; the project-wide
        // convention is 403, not 401, for anonymous on cabinet routes).
        Project p = saveProject(USER_ID, "Acme");

        mockMvc.perform(get("/api/v1/projects/" + p.getId() + "/api-key"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects/" + p.getId() + "/api-key").with(csrf()))
                .andExpect(status().isForbidden());

        // No key created by the rejected requests.
        Optional<ApiKey> stored = apiKeyRepository.findByProjectId(p.getId());
        assertThat(stored).isEmpty();
    }
}
