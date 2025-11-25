package com.example.urlshortener.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test security configuration that disables all security for unit tests.
 * This allows @WebMvcTest tests to run without authentication.
 */
@TestConfiguration
@EnableWebSecurity

public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());

        return http.build();
    }

    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        var user = org.springframework.security.core.userdetails.User.withUsername("testuser")
                .password("{noop}password")
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
                .build();

        return new org.springframework.security.provisioning.InMemoryUserDetailsManager(user);
    }
}
