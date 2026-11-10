package com.hotel.workload.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ManagerDepartmentChangeRepository extends JpaRepository<ManagerDepartmentChange, UUID> {

    /** One Manager's changes, newest first. */
    @Query("SELECT c FROM ManagerDepartmentChange c WHERE c.managerUserId = :userId ORDER BY c.changedAt DESC, c.id DESC")
    List<ManagerDepartmentChange> findHistory(@Param("userId") UUID userId);
}
