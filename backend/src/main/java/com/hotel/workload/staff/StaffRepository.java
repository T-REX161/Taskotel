package com.hotel.workload.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, UUID> {
    boolean existsByEmail(String email);

    /** Every staff member with their user account loaded, for callers that read the account (no per-row lookups). */
    @Query("SELECT s FROM Staff s JOIN FETCH s.user")
    List<Staff> findAllWithUser();

    /**
     * The staff of one department whose login account has the given role, with the account loaded. The department filter is part of the
     * query, so a department-scoped caller never loads other departments' staff.
     */
    @Query("SELECT s FROM Staff s JOIN FETCH s.user u WHERE s.department = :department AND u.role = :role")
    List<Staff> findByDepartmentAndUserRole(@Param("department") Department department,
                                            @Param("role") com.hotel.workload.security.Role role);

    /** Locks these staff rows (in id order) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Staff s WHERE s.id IN :ids ORDER BY s.id")
    List<Staff> lockByIds(@Param("ids") Collection<UUID> ids);

    /**
     * Locks one staff member of the department until the transaction ends, or returns nothing when the member does not exist or belongs
     * to another department, so a department-scoped caller never locks someone outside their department. PostgreSQL re-checks the
     * department once it has the row lock, so a transfer committed meanwhile is seen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Staff s WHERE s.id = :id AND s.department = :department")
    Optional<Staff> lockByIdAndDepartment(@Param("id") UUID id, @Param("department") Department department);

    /** The home-department codes of these staff members, without loading the records. */
    @Query("SELECT DISTINCT s.department.code FROM Staff s WHERE s.id IN :ids")
    List<String> departmentCodesOf(@Param("ids") Collection<UUID> ids);
    Optional<Staff> findByUserUsername(String username);
    
    // Operational staff only: records linked to an admin or manager account are left out of the dashboard staff totals
    @Query("SELECT COUNT(s) FROM Staff s WHERE s.user.role NOT IN (com.hotel.workload.security.Role.ADMIN, com.hotel.workload.security.Role.MANAGER)")
    long countOperational();

    @Query("SELECT COUNT(s) FROM Staff s WHERE s.active = false AND s.user.role NOT IN (com.hotel.workload.security.Role.ADMIN, com.hotel.workload.security.Role.MANAGER)")
    long countOperationalInactive();

    @Query("SELECT COUNT(s) FROM Staff s WHERE s.active = true AND s.availability = :availability AND s.user.role NOT IN (com.hotel.workload.security.Role.ADMIN, com.hotel.workload.security.Role.MANAGER)")
    long countOperationalActiveByAvailability(@Param("availability") Availability availability);

    /** Every active staff member of one department with their account loaded. */
    @Query("SELECT s FROM Staff s JOIN FETCH s.user WHERE s.department = :department AND s.active = true")
    List<Staff> findActiveWithUserByDepartment(@Param("department") Department department);

    /** Every active staff member with the given availability (any department) with their account loaded: the people who could be free to help. */
    @Query("SELECT s FROM Staff s JOIN FETCH s.user WHERE s.active = true AND s.availability = :availability")
    List<Staff> findActiveWithUserByAvailability(@Param("availability") Availability availability);

    // Department-scoped operational counts (Manager dashboard): the filter is in the query, never applied afterwards in Java.
    @Query("SELECT COUNT(s) FROM Staff s WHERE s.department = :department AND s.user.role = com.hotel.workload.security.Role.STAFF")
    long countOperationalInDepartment(@Param("department") Department department);

    @Query("SELECT COUNT(s) FROM Staff s WHERE s.department = :department AND s.active = false AND s.user.role = com.hotel.workload.security.Role.STAFF")
    long countOperationalInactiveInDepartment(@Param("department") Department department);

    @Query("SELECT COUNT(s) FROM Staff s WHERE s.department = :department AND s.active = true AND s.availability = :availability AND s.user.role = com.hotel.workload.security.Role.STAFF")
    long countOperationalActiveByAvailabilityInDepartment(@Param("department") Department department, @Param("availability") Availability availability);

    long countByActiveFalse();
    long countByActiveTrueAndAvailability(Availability availability);
    long countByDepartmentAndActiveTrue(Department department);

    /** Every staff member in the department, active or not (they all reference it). */
    long countByDepartment(Department department);
}
