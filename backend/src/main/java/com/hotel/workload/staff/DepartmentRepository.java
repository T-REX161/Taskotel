package com.hotel.workload.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    Optional<Department> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /** Original six first (they are created together, in order), then newer departments in creation order. */
    List<Department> findAllByOrderByCreatedAtAscNameAsc();

    /** Departments that name this staff member as their head. */
    List<Department> findByHeadStaffId(UUID staffId);
}
