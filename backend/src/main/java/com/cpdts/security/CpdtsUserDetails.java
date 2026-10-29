package com.cpdts.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * UserDetails used only during login authentication (DaoAuthenticationProvider).
 *
 * <p>Design notes:
 * <ul>
 *   <li>{@code isEnabled()} always returns true on purpose: the account-status
 *       check happens AFTER password verification in AuthService, so that a
 *       disabled account is only disclosed to someone who already knows the
 *       correct password (no account-enumeration oracle for wrong passwords).</li>
 *   <li>No authorities: authorization is company-scoped and permission-based,
 *       resolved per request from the database.</li>
 * </ul>
 */
public final class CpdtsUserDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String email;
    private final String fullName;
    private final String passwordHash;

    public CpdtsUserDetails(long userId, String email, String fullName, String passwordHash) {
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
    }

    public long userId() {
        return userId;
    }

    public String fullName() {
        return fullName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        // See class javadoc: status is checked after password verification.
        return true;
    }
}
