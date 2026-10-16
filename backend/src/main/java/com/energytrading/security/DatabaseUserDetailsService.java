package com.energytrading.security;

import com.energytrading.repository.UserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads accounts from the {@code users} table. The username is the household code
 * (e.g. {@code HH-001}); the authority is the stored role (e.g. {@code ROLE_HOUSEHOLD}).
 * Passwords are verified against the stored BCrypt hash and never leave this class.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByHouseholdCode(username)
                .map(user -> User.withUsername(user.getHouseholdCode())
                        .password(user.getPasswordHash())
                        .authorities(user.getRole().name())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
