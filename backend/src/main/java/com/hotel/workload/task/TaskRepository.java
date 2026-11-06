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

    /** Tasks of one department in one status (tasks with no department never match). Used by the Manager dashboard. */
    long countByDepartmentAndStatus(com.hotel.workload.staff.Department department, TaskStatus status);

    @Query("SELECT DISTINCT a.task FROM Assignment a WHERE a.staff.id = :staffId")
    List<Task> findTasksByStaffId(@Param("staffId") UUID staffId);

    boolean existsByCreatedById(UUID userId);

    /**
     * Every task of one department. The department filter is part of the query, so a department-scoped caller never loads other
     * departments' tasks, and tasks with no department (null never equals a department) are never returned.
     */
    List<Task> findByDepartment(com.hotel.workload.staff.Department department);

    /**
     * Locks one task of the department until the transaction ends, or returns nothing when the task does not exist, belongs to another
     * department or has no department. A task outside the department is therefore never locked. PostgreSQL re-checks the department once
     * it has the row lock, so a department change committed meanwhile is seen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id AND t.department = :department")
    java.util.Optional<Task> lockByIdAndDepartment(@Param("id") UUID id, @Param("department") com.hotel.workload.staff.Department department);

    /**
     * Active tasks of one department that nobody holds (no assignment row): the department's "waiting" work. The department filter is in the
     * query, so a Manager's recommendation never loads another department's tasks, and tasks with no department never match.
     */
    @Query("SELECT t FROM Task t WHERE t.department = :department AND t.status IN :statuses AND NOT EXISTS (SELECT a.id FROM Assignment a WHERE a.task = t)")
    List<Task> findWaitingByDepartment(@Param("department") com.hotel.workload.staff.Department department, @Param("statuses") Collection<TaskStatus> statuses);

    /**
     * How many tasks wait (active, nobody holds them) in each department, as [department code, count] rows. Counts only: no task detail is
     * returned. Needed so a donor department's reserve rule can be applied without loading its tasks.
     */
    @Query("SELECT t.department.code, COUNT(t) FROM Task t WHERE t.department IS NOT NULL AND t.status IN :statuses AND NOT EXISTS (SELECT a.id FROM Assignment a WHERE a.task = t) GROUP BY t.department.code")
    List<Object[]> countWaitingByDepartmentCode(@Param("statuses") Collection<TaskStatus> statuses);
}
