package com.hotel.workload.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ManagerViewGrantRepository extends JpaRepository<ManagerViewGrant, UUID> {

    /** How many Manager additional-department grants reference this department (any Manager, valid or not). */
    @Query("SELECT COUNT(g) FROM ManagerViewGrant g WHERE g.department = :department")
    long countByDepartment(@Param("department") Department department);

    /** Every grant of one Manager, with its department, in department order. */
    @Query("SELECT g FROM ManagerViewGrant g JOIN FETCH g.department d WHERE g.manager.id = :managerUserId ORDER BY d.name ASC")
    List<ManagerViewGrant> findByManagerId(@Param("managerUserId") UUID managerUserId);

    /**
     * The accounts of the Managers who manage this department through an Administrator grant and are valid right now: the department is
     * active, the account is an active Manager, and their own primary department is valid too (an active linked Staff record in an active
     * department: with an inactive primary department a Manager has no department access at all, whatever they were granted).
     */
    @Query("SELECT DISTINCT u.id FROM ManagerViewGrant g JOIN g.manager u, Staff s WHERE g.department = :department AND g.department.active = true "
            + "AND u.active = true AND u.role = com.hotel.workload.security.Role.MANAGER AND s.user = u AND s.active = true "
            + "AND s.department IS NOT NULL AND s.department.active = true")
    List<UUID> findValidManagerUserIdsByGrantedDepartment(@Param("department") Department department);

    /** The departments one Manager may currently view through grants: active departments only, read on every request. */
    @Query("SELECT d FROM ManagerViewGrant g JOIN g.department d WHERE g.manager.id = :managerUserId AND d.active = true ORDER BY d.name ASC")
    List<Department> findActiveGrantedDepartments(@Param("managerUserId") UUID managerUserId);
}
