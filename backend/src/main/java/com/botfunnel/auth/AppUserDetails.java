package com.botfunnel.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

// Carries only id/email/name/status. Excludes passwordHash and token-hash fields so
// the SecurityContext (serialized into MongoDB sessions.attributes) does not become a
// secondary store of password hashes.
//
// getUsername() returns the user id (Mongo ObjectId string), not the email — Spring Session
// stores this value in sessions.principal, and Tasks 6/7 query sessions by this value.
public record AppUserDetails(String id, String email, String name, String status)
        implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }

    @Override
    public String getPassword() {
        // No credentials material is held by the principal — bcrypt verification has already happened
        // before this principal is constructed; downstream code must not consume getPassword().
        return null;
    }

    @Override
    public String getUsername() {
        return id;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
