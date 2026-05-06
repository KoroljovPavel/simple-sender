package com.botfunnel.profile;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Spring Security Test integration: each annotated method runs with an Authentication
// in the SecurityContext whose principal is an AppUserDetails record (so the controller's
// `instanceof AppUserDetails` check passes). The userId placed here is overwritten per-test
// via WithMockAppUserSecurityContextFactory so the test can match against a real seeded user.
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockAppUserSecurityContextFactory.class)
public @interface WithMockAppUser {
    String userId() default "test-user-id";
    String email() default "user@test.com";
    String name() default "Test User";
    String status() default "active";
}
