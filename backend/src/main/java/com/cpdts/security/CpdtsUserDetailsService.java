package com.cpdts.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.cpdts.repository.UserRepository;
import com.cpdts.repository.UserRepository.UserRow;

/**
 * Loads users for login authentication by email (the login identifier in the
 * users table).
 *
 * <p>Declaring this bean also makes Spring Boot's user-password
 * auto-configuration back off, so no random "generated password" is ever
 * printed to the logs.
 */
@Service
public class CpdtsUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public CpdtsUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserRow user = users.findByEmail(EmailNormalizer.normalize(email))
                .orElseThrow(() -> new UsernameNotFoundException("user not found"));
        return new CpdtsUserDetails(user.id(), user.email(), user.fullName(), user.passwordHash());
    }
}
