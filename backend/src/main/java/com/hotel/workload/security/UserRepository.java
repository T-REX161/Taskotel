package com.hotel.workload.security;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Optional<User> findBySetupToken(String setupToken);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
