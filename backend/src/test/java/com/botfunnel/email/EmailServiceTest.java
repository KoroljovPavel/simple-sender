package com.botfunnel.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    JavaMailSender javaMailSender;

    EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(javaMailSender, "noreply@test.com", "http://localhost:3000");
    }

    private MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getDefaultInstance(new Properties()));
    }

    @Test
    void sendVerificationEmail_callsJavaMailSenderWithCorrectRecipientAndFrom() throws Exception {
        MimeMessage mimeMessage = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendVerificationEmail("user@example.com", "Alice", "token123");

        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );

        assertThat(mimeMessage.getAllRecipients()).hasSize(1);
        assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(mimeMessage.getFrom()[0].toString()).isEqualTo("noreply@test.com");
    }

    @Test
    void sendVerificationEmail_htmlEscapesNameWithSpecialChars() throws Exception {
        MimeMessage mimeMessage = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendVerificationEmail("user@example.com", "<script>alert('xss')</script>", "token123");

        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );

        String body = extractHtmlBody(mimeMessage);
        assertThat(body).contains("&lt;script&gt;");
        assertThat(body).doesNotContain("<script>");
    }

    @Test
    void sendVerificationEmail_bodyContainsVerificationUrl() {
        String body = emailService.buildVerificationBody("Alice", "mytoken");
        assertThat(body).contains("http://localhost:3000/auth/verify-email?token=mytoken");
    }

    @Test
    void sendVerificationEmail_fireAndForget_exceptionDoesNotPropagate() {
        MimeMessage mimeMessage = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("SMTP error")).when(javaMailSender).send(any(MimeMessage.class));

        assertThatNoException().isThrownBy(() ->
            emailService.sendVerificationEmail("user@example.com", "Alice", "token123")
        );

        // Verify send was attempted and exception was swallowed by subscriber
        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );
    }

    @Test
    void sendPasswordResetEmail_callsJavaMailSenderWithCorrectResetUrl() throws Exception {
        MimeMessage mimeMessage = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendPasswordResetEmail("user@example.com", "Alice", "resettoken");

        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );

        assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(mimeMessage.getSubject()).isEqualTo("Скидання пароля");
        String body = extractHtmlBody(mimeMessage);
        assertThat(body).contains("http://localhost:3000/auth/reset-password?token=resettoken");
    }

    @Test
    void sendAccountBlockedEmail_callsJavaMailSenderWithSupportEmail() throws Exception {
        MimeMessage mimeMessage = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendAccountBlockedEmail("user@example.com", "Alice", "support@test.com");

        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );

        assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(mimeMessage.getSubject()).isEqualTo("Ваш акаунт заблоковано");
        String body = extractHtmlBody(mimeMessage);
        assertThat(body).contains("support@test.com");
    }

    @Test
    void sendVerificationEmail_templateNotFound_noEmailSent() {
        EmailService spyService = spy(emailService);
        doReturn("").when(spyService).loadTemplate(anyString());

        spyService.sendVerificationEmail("user@example.com", "Alice", "token123");

        verify(javaMailSender, never()).createMimeMessage();
    }

    @Test
    void buildPasswordResetBody_htmlEscapesName() {
        String body = emailService.buildPasswordResetBody("<script>xss</script>", "token");
        assertThat(body).contains("&lt;script&gt;");
        assertThat(body).doesNotContain("<script>");
    }

    @Test
    void buildAccountBlockedBody_htmlEscapesName() {
        String body = emailService.buildAccountBlockedBody("<script>xss</script>", "support@test.com");
        assertThat(body).contains("&lt;script&gt;");
        assertThat(body).doesNotContain("<script>");
    }

    @Test
    void htmlEscape_escapesSingleQuote() {
        assertThat(EmailService.htmlEscape("O'Brien")).isEqualTo("O&#39;Brien");
    }

    @Test
    void buildVerificationBody_htmlEscapesToken() {
        String body = emailService.buildVerificationBody("Alice", "<img src=x onerror=alert()>");
        assertThat(body).doesNotContain("<img");
        assertThat(body).contains("&lt;img");
    }

    // saveChanges() causes MimeMessage to write Content-Type headers from DataHandlers,
    // making isMimeType() checks reliable for in-memory MimeMessages.
    private String extractHtmlBody(MimeMessage message) throws Exception {
        message.saveChanges();
        Object content = message.getContent();
        if (content instanceof String html) return html;
        if (content instanceof MimeMultipart mp) return findHtmlPart(mp);
        return "";
    }

    private String findHtmlPart(MimeMultipart mp) throws Exception {
        for (int i = 0; i < mp.getCount(); i++) {
            var part = mp.getBodyPart(i);
            if (part.isMimeType("multipart/*")) {
                String found = findHtmlPart((MimeMultipart) part.getContent());
                if (!found.isEmpty()) return found;
            } else if (part.isMimeType("text/html")) {
                try (InputStream is = part.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
