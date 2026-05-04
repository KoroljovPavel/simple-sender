package com.botfunnel.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

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
    void sendVerificationEmail_htmlEscapesNameWithSpecialChars() {
        // buildVerificationBody is what sendVerificationEmail delegates to — verifies htmlEscape is wired
        String body = emailService.buildVerificationBody("<script>alert('xss')</script>", "token123");
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
        lenient().when(javaMailSender.createMimeMessage()).thenReturn(newMimeMessage());
        lenient().doThrow(new MailSendException("SMTP error")).when(javaMailSender).send(any(MimeMessage.class));

        assertThatNoException().isThrownBy(() ->
            emailService.sendVerificationEmail("user@example.com", "Alice", "token123")
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
        assertThat(emailService.buildPasswordResetBody("Alice", "resettoken"))
                .contains("http://localhost:3000/auth/reset-password?token=resettoken");
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
        assertThat(emailService.buildAccountBlockedBody("Alice", "support@test.com"))
                .contains("support@test.com");
    }

    @Test
    void sendVerificationEmail_templateNotFound_noEmailSent() {
        EmailService spyService = spy(emailService);
        doReturn("").when(spyService).loadTemplate(anyString());

        spyService.sendVerificationEmail("user@example.com", "Alice", "token123");

        verify(javaMailSender, never()).createMimeMessage();
    }
}
