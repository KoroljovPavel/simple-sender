package com.botfunnel.profile;

import com.botfunnel.auth.AppUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

public class WithMockAppUserSecurityContextFactory implements WithSecurityContextFactory<WithMockAppUser> {
    @Override
    public SecurityContext createSecurityContext(WithMockAppUser annotation) {
        AppUserDetails principal = new AppUserDetails(
                annotation.userId(), annotation.email(), annotation.name(), annotation.status());
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext ctx = new SecurityContextImpl();
        ctx.setAuthentication(auth);
        return ctx;
    }
}
