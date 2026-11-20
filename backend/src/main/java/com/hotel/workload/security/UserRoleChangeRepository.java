package com.hotel.workload.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Read and append only: nothing in the application updates or deletes an audit record. */
public interface UserRoleChangeRepository extends JpaRepository<UserRoleChange, UUID> {

    List<UserRoleChange> findByTargetUserIdOrderByChangedAtDesc(UUID targetUserId);
}
