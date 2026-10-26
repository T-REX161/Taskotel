package com.hotel.workload.security;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    // A list: the unique constraint on email is case-sensitive, so two accounts could differ only in case
    List<User> findAllByEmailIgnoreCase(String email);
    Optional<User> findBySetupToken(String setupToken);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
