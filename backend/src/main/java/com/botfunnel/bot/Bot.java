package com.botfunnel.bot;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true (dev).
// Partial-unique indexes use the persisted Java name() form "CONNECTED" — Spring Data
// MongoDB enum mapping is independent of Jackson, so the BotStatus @JsonValue lowercase
// annotation only affects HTTP serialisation. The simple @Indexed on projectId keeps
// findByProjectId() lookups fast even when the partial-unique compound excludes the row.
@Document(collection = "bots")
@CompoundIndexes({
        @CompoundIndex(name = "projectId_status", def = "{'projectId': 1, 'status': 1}"),
        @CompoundIndex(name = "telegramBotId_unique_connected",
                def = "{'telegramBotId': 1}",
                unique = true,
                partialFilter = "{ 'status': 'CONNECTED' }"),
        @CompoundIndex(name = "projectId_unique_connected",
                def = "{'projectId': 1}",
                unique = true,
                partialFilter = "{ 'status': 'CONNECTED' }")
})
public class Bot {

    @Id
    private String id;

    @Indexed
    private String projectId;

    private Long telegramBotId;
    private String telegramUsername;
    private String telegramFirstName;
    private BotStatus status;

    private String encryptedTokenCiphertext;
    private String encryptedTokenIv;
    private String tokenSuffix;
    private String webhookSecretHash;

    private Instant connectedAt;
    private Instant disconnectedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Long getTelegramBotId() { return telegramBotId; }
    public void setTelegramBotId(Long telegramBotId) { this.telegramBotId = telegramBotId; }

    public String getTelegramUsername() { return telegramUsername; }
    public void setTelegramUsername(String telegramUsername) { this.telegramUsername = telegramUsername; }

    public String getTelegramFirstName() { return telegramFirstName; }
    public void setTelegramFirstName(String telegramFirstName) { this.telegramFirstName = telegramFirstName; }

    public BotStatus getStatus() { return status; }
    public void setStatus(BotStatus status) { this.status = status; }

    public String getEncryptedTokenCiphertext() { return encryptedTokenCiphertext; }
    public void setEncryptedTokenCiphertext(String encryptedTokenCiphertext) { this.encryptedTokenCiphertext = encryptedTokenCiphertext; }

    public String getEncryptedTokenIv() { return encryptedTokenIv; }
    public void setEncryptedTokenIv(String encryptedTokenIv) { this.encryptedTokenIv = encryptedTokenIv; }

    public String getTokenSuffix() { return tokenSuffix; }
    public void setTokenSuffix(String tokenSuffix) { this.tokenSuffix = tokenSuffix; }

    public String getWebhookSecretHash() { return webhookSecretHash; }
    public void setWebhookSecretHash(String webhookSecretHash) { this.webhookSecretHash = webhookSecretHash; }

    public Instant getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }

    public Instant getDisconnectedAt() { return disconnectedAt; }
    public void setDisconnectedAt(Instant disconnectedAt) { this.disconnectedAt = disconnectedAt; }
}
