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

    /** Locks these staff rows (in id order) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Staff s WHERE s.id IN :ids ORDER BY s.id")
    List<Staff> lockByIds(@Param("ids") Collection<UUID> ids);

    /** The home-department codes of these staff members, without loading the records. */
    @Query("SELECT DISTINCT s.department.code FROM Staff s WHERE s.id IN :ids")
    List<String> departmentCodesOf(@Param("ids") Collection<UUID> ids);
    Optional<Staff> findByUserUsername(String username);
    
    long countByActiveFalse();
    long countByActiveTrueAndAvailability(Availability availability);
    long countByDepartmentAndActiveTrue(Department department);

    /** Every staff member in the department, active or not (they all reference it). */
    long countByDepartment(Department department);
}
