package com.botfunnel.bot;

import com.botfunnel.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotIndexTest extends AbstractIntegrationTest {

    @Autowired BotRepository botRepository;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clean() {
        botRepository.deleteAll();
    }

    private Bot connectedBot(String projectId, Long telegramBotId) {
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(telegramBotId);
        b.setStatus(BotStatus.CONNECTED);
        b.setEncryptedTokenCiphertext("ct");
        b.setEncryptedTokenIv("iv");
        b.setTokenSuffix("xyz");
        b.setWebhookSecretHash("hash");
        b.setConnectedAt(Instant.now());
        return b;
    }

    private Bot disconnectedBot(String projectId, Long telegramBotId) {
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(telegramBotId);
        b.setStatus(BotStatus.DISCONNECTED);
        b.setConnectedAt(Instant.now().minusSeconds(60));
        b.setDisconnectedAt(Instant.now());
        return b;
    }

    @Test
    void partialUniqueIndex_telegramBotId_rejectsSecondConnectedRowWithSameTelegramId() {
        botRepository.save(connectedBot("proj-A", 999L));

        assertThatThrownBy(() -> botRepository.save(connectedBot("proj-B", 999L)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void partialUniqueIndex_telegramBotId_allowsDisconnectedDuplicate() {
        botRepository.save(connectedBot("proj-C", 1001L));

        Bot saved = botRepository.save(disconnectedBot("proj-D", 1001L));
        assertThat(saved.getTelegramBotId()).isEqualTo(1001L);
    }

    @Test
    void partialUniqueIndex_projectId_rejectsSecondConnectedRowForSameProject() {
        botRepository.save(connectedBot("proj-E", 1111L));

        assertThatThrownBy(() -> botRepository.save(connectedBot("proj-E", 2222L)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void partialUniqueIndex_projectId_allowsDisconnectedDuplicate() {
        botRepository.save(connectedBot("proj-F", 3333L));

        Bot saved = botRepository.save(disconnectedBot("proj-F", 4444L));
        assertThat(saved.getProjectId()).isEqualTo("proj-F");
    }

    @Test
    void indexes_areCreatedOnCollection() {
        List<IndexInfo> indexInfos = mongoTemplate.indexOps(Bot.class).getIndexInfo();
        assertThat(indexInfos).isNotNull();

        assertThat(indexNames(indexInfos))
                .contains("projectId_status", "telegramBotId_unique_connected", "projectId_unique_connected");

        IndexInfo telegramUnique = findByName(indexInfos, "telegramBotId_unique_connected");
        assertThat(telegramUnique.isUnique()).isTrue();
        assertThat(partialFilterStatus(telegramUnique)).isEqualTo("CONNECTED");

        IndexInfo projectUnique = findByName(indexInfos, "projectId_unique_connected");
        assertThat(projectUnique.isUnique()).isTrue();
        assertThat(partialFilterStatus(projectUnique)).isEqualTo("CONNECTED");

        IndexInfo projectStatus = findByName(indexInfos, "projectId_status");
        assertThat(projectStatus.isUnique()).isFalse();
    }

    private static List<String> indexNames(List<IndexInfo> indexInfos) {
        return indexInfos.stream().map(IndexInfo::getName).toList();
    }

    private static IndexInfo findByName(List<IndexInfo> indexInfos, String name) {
        Optional<IndexInfo> match = indexInfos.stream().filter(i -> name.equals(i.getName())).findFirst();
        assertThat(match).as("index %s present", name).isPresent();
        return match.get();
    }

    private static String partialFilterStatus(IndexInfo indexInfo) {
        String pf = indexInfo.getPartialFilterExpression();
        assertThat(pf).as("partial filter on %s", indexInfo.getName()).isNotBlank();
        Document parsed = Document.parse(pf);
        return parsed.getString("status");
    }
}
