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
    void sendVerificationEmail_callsJavaMailSenderWithCorrectRecipient() throws Exception {
        MimeMessage mimeMessage = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendVerificationEmail("user@example.com", "Alice", "token123");

        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );

        assertThat(mimeMessage.getAllRecipients()).hasSize(1);
        assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
    }

    @Test
    void sendVerificationEmail_htmlEscapesNameWithSpecialChars() {
        // htmlEscape is package-private — verified directly since sendVerificationEmail delegates to it
        String escaped = EmailService.htmlEscape("<script>alert('xss')</script>");
        assertThat(escaped).contains("&lt;script&gt;");
        assertThat(escaped).doesNotContain("<script>");
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
    void sendPasswordResetEmail_callsJavaMailSender() {
        when(javaMailSender.createMimeMessage()).thenReturn(newMimeMessage());

        emailService.sendPasswordResetEmail("user@example.com", "Alice", "resettoken");

        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );
    }

    @Test
    void sendAccountBlockedEmail_callsJavaMailSender() {
        when(javaMailSender.createMimeMessage()).thenReturn(newMimeMessage());

        emailService.sendAccountBlockedEmail("user@example.com", "Alice", "support@test.com");

        await().atMost(2, SECONDS).untilAsserted(() ->
            verify(javaMailSender).send(any(MimeMessage.class))
        );
    }
}
