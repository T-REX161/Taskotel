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

    /** Open tasks (in one of these statuses) with a due date on or before {@code latest}: the candidates for task reminders. */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.department WHERE t.dueDate IS NOT NULL AND t.dueDate <= :latest AND t.status IN :statuses ORDER BY t.id")
    List<Task> findOpenDueOnOrBefore(@Param("latest") java.time.LocalDate latest, @Param("statuses") Collection<TaskStatus> statuses);

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
     * Every task of these departments: the departments a Manager manages (their primary and any Administrator-granted ones). The filter is part of
     * the query, so no other department is loaded, and tasks with no department are never returned.
     */
    List<Task> findByDepartmentIn(Collection<com.hotel.workload.staff.Department> departments);

    /**
     * Locks one task of the department until the transaction ends, or returns nothing when the task does not exist, belongs to another
     * department or has no department. A task outside the department is therefore never locked. PostgreSQL re-checks the department once
     * it has the row lock, so a department change committed meanwhile is seen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id AND t.department = :department")
    java.util.Optional<Task> lockByIdAndDepartment(@Param("id") UUID id, @Param("department") com.hotel.workload.staff.Department department);

    /**
     * As {@link #lockByIdAndDepartment}, for the departments a Manager manages ({@code DepartmentScope.managedDepartments()}): locks the task
     * only when it belongs to one of them; a task of any other department, or with no department, is never locked and is not returned.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id AND t.department IN :departments")
    java.util.Optional<Task> lockByIdAndDepartmentIn(@Param("id") UUID id,
                                                     @Param("departments") Collection<com.hotel.workload.staff.Department> departments);

    /**
     * Active tasks of one department that nobody holds (no assignment row): the department's "waiting" work. The department filter is in the
     * query, so a Manager's recommendation never loads another department's tasks, and tasks with no department never match.
     */
    @Query("SELECT t FROM Task t WHERE t.department = :department AND t.status IN :statuses AND NOT EXISTS (SELECT a.id FROM Assignment a WHERE a.task = t)")
    List<Task> findWaitingByDepartment(@Param("department") com.hotel.workload.staff.Department department, @Param("statuses") Collection<TaskStatus> statuses);

    /**
     * The tasks a Staff member of {@code department} may claim: that department's tasks in {@code status} (PENDING) that nobody holds. The
     * department, the status and "no assignment row" are all in the query, so another department's task, a held task and a finished one are
     * never loaded, and a task with no department never matches. Oldest first.
     */
    @Query("SELECT t FROM Task t WHERE t.department = :department AND t.status = :status AND NOT EXISTS (SELECT a.id FROM Assignment a WHERE a.task = t) ORDER BY t.createdAt ASC, t.id ASC")
    List<Task> findClaimableByDepartment(@Param("department") com.hotel.workload.staff.Department department, @Param("status") TaskStatus status);

    /**
     * The active tasks of these departments that someone already holds (at least one assignment). Used only to offer a helper a task of an
     * overloaded department to join; limited by department in the query, so no other department's task is loaded. A task a Staff member accepted
     * themselves (Option A, see Assignment#isSelfClaimed) is never returned: it belongs to them alone, so no helper may join it.
     */
    @Query("SELECT t FROM Task t WHERE t.department IN :departments AND t.status IN :statuses AND EXISTS (SELECT a.id FROM Assignment a WHERE a.task = t) "
            + "AND NOT EXISTS (SELECT s.id FROM Assignment s WHERE s.task = t AND s.selfClaimed = true)")
    List<Task> findHeldByDepartments(@Param("departments") Collection<com.hotel.workload.staff.Department> departments, @Param("statuses") Collection<TaskStatus> statuses);

    /**
     * How many tasks wait (active, nobody holds them) in each department, as [department code, count] rows. Counts only: no task detail is
     * returned. Needed so a donor department's reserve rule can be applied without loading its tasks.
     */
    @Query("SELECT t.department.code, COUNT(t) FROM Task t WHERE t.department IS NOT NULL AND t.status IN :statuses AND NOT EXISTS (SELECT a.id FROM Assignment a WHERE a.task = t) GROUP BY t.department.code")
    List<Object[]> countWaitingByDepartmentCode(@Param("statuses") Collection<TaskStatus> statuses);

    /**
     * The estimated minutes of the tasks that wait (active, nobody holds them) in each department, as [department code, minutes] rows. A task
     * with no estimate counts as 0. Sums only: no task detail is returned. Part of a department's remaining workload (see DepartmentCapacity).
     */
    @Query("SELECT t.department.code, COALESCE(SUM(COALESCE(t.estimatedDuration, 0)), 0) FROM Task t WHERE t.department IS NOT NULL AND t.status IN :statuses AND NOT EXISTS (SELECT a.id FROM Assignment a WHERE a.task = t) GROUP BY t.department.code")
    List<Object[]> sumWaitingMinutesByDepartmentCode(@Param("statuses") Collection<TaskStatus> statuses);
}
