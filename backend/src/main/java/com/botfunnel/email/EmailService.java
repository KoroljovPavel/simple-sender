package com.botfunnel.email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender javaMailSender;
    private final String from;
    private final String appUrl;

    public EmailService(JavaMailSender javaMailSender,
                        @Value("${app.mail.from}") String from,
                        @Value("${app.url}") String appUrl) {
        this.javaMailSender = javaMailSender;
        this.from = from;
        this.appUrl = appUrl;
    }

    public void sendVerificationEmail(String to, String name, String token) {
        String body = buildVerificationBody(name, token);
        if (body.isEmpty()) return;
        sendAsync(to, "Підтвердіть email", body);
    }

    public void sendPasswordResetEmail(String to, String name, String token) {
        String body = buildPasswordResetBody(name, token);
        if (body.isEmpty()) return;
        sendAsync(to, "Скидання пароля", body);
    }

    public void sendAccountBlockedEmail(String to, String name, String supportEmail) {
        String body = buildAccountBlockedBody(name, supportEmail);
        if (body.isEmpty()) return;
        sendAsync(to, "Ваш акаунт заблоковано", body);
    }

    String buildVerificationBody(String name, String token) {
        String template = loadTemplate("/templates/email/verify-email.html");
        if (template.isEmpty()) return "";
        return template
                .replace("{NAME}", htmlEscape(name))
                .replace("{TOKEN}", htmlEscape(token))
                .replace("{APP_URL}", appUrl);
    }

    String buildPasswordResetBody(String name, String token) {
        String template = loadTemplate("/templates/email/reset-password.html");
        if (template.isEmpty()) return "";
        return template
                .replace("{NAME}", htmlEscape(name))
                .replace("{TOKEN}", htmlEscape(token))
                .replace("{APP_URL}", appUrl);
    }

    String buildAccountBlockedBody(String name, String supportEmail) {
        String template = loadTemplate("/templates/email/account-blocked.html");
        if (template.isEmpty()) return "";
        return template
                .replace("{NAME}", htmlEscape(name))
                .replace("{SUPPORT_EMAIL}", htmlEscape(supportEmail));
    }

    private void sendAsync(String to, String subject, String htmlBody) {
        Mono.fromCallable(() -> {
            MimeMessage msg = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            javaMailSender.send(msg);
            return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(null, err -> log.error("Email send failed: {}", err.getMessage()));
    }

    String loadTemplate(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                log.error("Email template not found: {}", path);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load email template {}: {}", path, e.getMessage());
            return "";
        }
    }

    static String htmlEscape(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
