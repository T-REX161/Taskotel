package com.hotel.workload.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ManagerViewGrantChangeRepository extends JpaRepository<ManagerViewGrantChange, UUID> {

    List<ManagerViewGrantChange> findByManagerUserIdOrderByChangedAtDesc(UUID managerUserId);
}
