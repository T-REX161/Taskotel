package com.hotel.workload.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, UUID> {
    boolean existsByEmail(String email);
    Optional<Staff> findByUserUsername(String username);
    
    long countByActiveFalse();
    long countByActiveTrueAndAvailability(Availability availability);
    long countByDepartmentAndActiveTrue(Department department);

    /** Every staff member in the department, active or not (they all reference it). */
    long countByDepartment(Department department);
}
