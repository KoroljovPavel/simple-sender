package com.botfunnel;

import ch.martinelli.oss.testcontainers.mailpit.MailpitContainer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Singleton-container base class. JUnit Jupiter creates a fresh ApplicationContext per test
// class by default; the static containers + static start() block ensure each container is
// launched once for the JVM and reused across all subclasses, which keeps the test suite fast.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import({AbstractIntegrationTest.MailpitTestConfig.class, JobRunrInMemoryConfig.class})
public abstract class AbstractIntegrationTest {

    // Boot's MailSenderAutoConfiguration binds spring.mail.host/port at bean creation. The
    // application.properties placeholder (${MAIL_HOST:localhost}) wins over @DynamicPropertySource
    // values for these specific keys (Mongo + Redis URIs work fine — only the mail keys are odd).
    // Direct @Primary bean override is the most reliable way to point JavaMailSender at the
    // testcontainer SMTP port.
    @TestConfiguration
    static class MailpitTestConfig {
        @Bean
        @Primary
        JavaMailSender mailpitJavaMailSender() {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(MAILPIT.getHost());
            sender.setPort(MAILPIT.getSmtpPort());
            return sender;
        }
    }

    @SuppressWarnings("resource")
    protected static final MongoDBContainer MONGO_DB =
            new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    @SuppressWarnings("resource")
    protected static final MailpitContainer MAILPIT =
            new MailpitContainer("axllent/mailpit:v1.29.7");

    static {
        MONGO_DB.start();
        REDIS.start();
        MAILPIT.start();
    }

    // JobRunr StorageProvider: supplied as an InMemoryStorageProvider by JobRunrInMemoryConfig.
    // The background-job-server is disabled in test profile (application-test.properties) so no
    // worker threads spawn. Recurring-job registration still runs at startup and is verified by
    // HardDeleteJobTest invoking the @Recurring method directly.
    @Autowired
    private ApplicationContext applicationContext;

    // The autowired RANDOM_PORT WebTestClient is bound-to-server, which breaks the
    // SecurityMockServerConfigurers.csrf() mutator (it requires bindToApplicationContext).
    // Re-bind per test to the in-memory ApplicationContext so csrf() works for IT POSTs,
    // matching the convention used by AuthControllerSliceTest.
    protected WebTestClient webTestClient;

    @BeforeEach
    void rebindWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .configureClient()
                .build();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO_DB::getReplicaSetUrl);
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
    }
}
