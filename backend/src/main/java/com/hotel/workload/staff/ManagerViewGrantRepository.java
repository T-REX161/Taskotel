package com.hotel.workload.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ManagerViewGrantRepository extends JpaRepository<ManagerViewGrant, UUID> {

    /** Every grant of one Manager, with its department, in department order. */
    @Query("SELECT g FROM ManagerViewGrant g JOIN FETCH g.department d WHERE g.manager.id = :managerUserId ORDER BY d.name ASC")
    List<ManagerViewGrant> findByManagerId(@Param("managerUserId") UUID managerUserId);

    /** The departments one Manager may currently view through grants: active departments only, read on every request. */
    @Query("SELECT d FROM ManagerViewGrant g JOIN g.department d WHERE g.manager.id = :managerUserId AND d.active = true ORDER BY d.name ASC")
    List<Department> findActiveGrantedDepartments(@Param("managerUserId") UUID managerUserId);
}
