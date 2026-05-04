package com.botfunnel.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

// Note: @Indexed(unique=true) on email requires manual index creation in production
// when spring.data.mongodb.auto-index-creation=false (the current global setting).
@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;
    private String name;

    @Indexed
    private UserStatus status;

    @Field("isSuperAdmin")
    private boolean superAdmin;

    // Token fields
    private String emailVerificationTokenHash;
    private Instant emailVerificationExpiresAt;
    private String passwordResetTokenHash;
    private Instant passwordResetExpiresAt;
    private Instant passwordResetUsedAt;

    // Timestamps — set manually in UserService
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public boolean isSuperAdmin() { return superAdmin; }
    public void setSuperAdmin(boolean superAdmin) { this.superAdmin = superAdmin; }

    public String getEmailVerificationTokenHash() { return emailVerificationTokenHash; }
    public void setEmailVerificationTokenHash(String emailVerificationTokenHash) { this.emailVerificationTokenHash = emailVerificationTokenHash; }

    public Instant getEmailVerificationExpiresAt() { return emailVerificationExpiresAt; }
    public void setEmailVerificationExpiresAt(Instant emailVerificationExpiresAt) { this.emailVerificationExpiresAt = emailVerificationExpiresAt; }

    public String getPasswordResetTokenHash() { return passwordResetTokenHash; }
    public void setPasswordResetTokenHash(String passwordResetTokenHash) { this.passwordResetTokenHash = passwordResetTokenHash; }

    public Instant getPasswordResetExpiresAt() { return passwordResetExpiresAt; }
    public void setPasswordResetExpiresAt(Instant passwordResetExpiresAt) { this.passwordResetExpiresAt = passwordResetExpiresAt; }

    public Instant getPasswordResetUsedAt() { return passwordResetUsedAt; }
    public void setPasswordResetUsedAt(Instant passwordResetUsedAt) { this.passwordResetUsedAt = passwordResetUsedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
