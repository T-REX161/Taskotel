package com.hotel.workload.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    Optional<Department> findByCode(String code);

    /** Locks these departments (in code order, so concurrent callers cannot deadlock) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Department d WHERE d.code IN :codes ORDER BY d.code")
    List<Department> lockByCodes(@Param("codes") Collection<String> codes);

    boolean existsByCode(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /** Original six first (they are created together, in order), then newer departments in creation order. */
    List<Department> findAllByOrderByCreatedAtAscNameAsc();

    /** Departments that name this staff member as their head. */
    List<Department> findByHeadStaffId(UUID staffId);
}
