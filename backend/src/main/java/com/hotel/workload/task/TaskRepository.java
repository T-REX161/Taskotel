package com.hotel.workload.task;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {
    long countByStatus(TaskStatus status);
    List<Task> findByStatusIn(Collection<TaskStatus> statuses);

    /** Locks these tasks (in id order) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id IN :ids ORDER BY t.id")
    List<Task> lockByIds(@Param("ids") Collection<UUID> ids);
    long countByDepartmentAndStatusIn(com.hotel.workload.staff.Department department, Collection<TaskStatus> statuses);

    /** Every task in the department, whatever its status (they all reference it). */
    long countByDepartment(com.hotel.workload.staff.Department department);

    @Query("SELECT DISTINCT a.task FROM Assignment a WHERE a.staff.id = :staffId")
    List<Task> findTasksByStaffId(@Param("staffId") UUID staffId);

    boolean existsByCreatedById(UUID userId);
}
