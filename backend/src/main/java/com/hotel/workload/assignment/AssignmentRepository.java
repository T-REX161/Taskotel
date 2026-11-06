package com.hotel.workload.assignment;

import com.hotel.workload.task.TaskStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
    boolean existsByTaskId(UUID taskId);
    Optional<Assignment> findByTaskId(UUID taskId);
    List<Assignment> findAllByTaskId(UUID taskId);

    /** The task of an assignment without loading the assignment, so it can be locked in the usual order before being read. */
    @Query("SELECT a.task.id FROM Assignment a WHERE a.id = :id")
    Optional<UUID> findTaskIdById(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM Assignment a WHERE a.task.id = :taskId")
    void deleteByTaskId(@Param("taskId") UUID taskId);

    @EntityGraph(attributePaths = {"task", "staff"})
    List<Assignment> findAllByOrderByAssignedAtDesc();

    /**
     * The assignments of every task in one department, newest first. The department filter is part of the query, so a department-scoped
     * caller never loads other departments' assignments, and tasks with no department are never matched. The holder's own department
     * plays no part (a task supported by lent staff is still the task's department's assignment).
     */
    @Query("SELECT a FROM Assignment a JOIN FETCH a.task t JOIN FETCH a.staff s WHERE t.department = :department ORDER BY a.assignedAt DESC")
    List<Assignment> findByTaskDepartmentOrderByAssignedAtDesc(@Param("department") com.hotel.workload.staff.Department department);

    /**
     * Fetch all assignments whose task status is in the supplied set.
     * Used for workload calculation to avoid N+1 queries.
     */
    @Query("SELECT a FROM Assignment a JOIN FETCH a.task t JOIN FETCH a.staff s WHERE t.status IN :statuses")
    List<Assignment> findAllActiveAssignments(@Param("statuses") List<TaskStatus> statuses);

    /**
     * The active assignments of one department's own work: the task belongs to the department AND the holder's home department is the
     * department. Used for a Manager's workload view, so work lent to another department (the task is elsewhere) and work on tasks with
     * no department are never loaded, and nothing is filtered afterwards in Java.
     */
    @Query("SELECT a FROM Assignment a JOIN FETCH a.task t JOIN FETCH a.staff s WHERE t.status IN :statuses AND t.department = :department AND s.department = :department")
    List<Assignment> findActiveByTaskAndStaffDepartment(@Param("statuses") List<TaskStatus> statuses,
                                                        @Param("department") com.hotel.workload.staff.Department department);

    /** Count form of {@link #findActiveByTaskAndStaffDepartment}: the same rows a Manager's workload counts, so the dashboard agrees with it. */
    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.task.status IN :statuses AND a.task.department = :department AND a.staff.department = :department")
    long countActiveByTaskAndStaffDepartment(@Param("statuses") List<TaskStatus> statuses,
                                             @Param("department") com.hotel.workload.staff.Department department);

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.task.status IN :statuses")
    long countActiveAssignments(@Param("statuses") List<TaskStatus> statuses);

    /** Assignments tied to the department through their task or their staff member. */
    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.task.department = :department OR a.staff.department = :department")
    long countByDepartment(@Param("department") com.hotel.workload.staff.Department department);

    List<Assignment> findByStaffId(UUID staffId);

    @EntityGraph(attributePaths = {"task", "staff"})
    List<Assignment> findByStaffIdOrderByAssignedAtDesc(UUID staffId);

    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM Assignment a WHERE a.staff.id = :staffId AND a.task.status IN :statuses")
    boolean existsByStaffIdAndTaskStatusIn(@Param("staffId") UUID staffId, @Param("statuses") List<TaskStatus> statuses);

    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM Assignment a WHERE a.staff.id = :staffId AND a.id <> :excludeAssignmentId AND a.task.status IN :statuses")
    boolean existsByStaffIdAndIdNotAndTaskStatusIn(@Param("staffId") UUID staffId, @Param("excludeAssignmentId") UUID excludeAssignmentId, @Param("statuses") List<TaskStatus> statuses);

    /** The ids of tasks of one department that somebody holds (active tasks only; ids only). */
    @Query("SELECT DISTINCT a.task.id FROM Assignment a WHERE a.task.department = :department AND a.task.status IN :statuses")
    List<UUID> findHeldTaskIdsByDepartment(@Param("department") com.hotel.workload.staff.Department department, @Param("statuses") List<TaskStatus> statuses);

    /**
     * The minutes of active work that count under one department in the support figures: work on a task that has an ACTIVE support record
     * into the department, plus work its own staff do on tasks that have no active support record. This is the same attribution the support
     * calculation uses (a supported task counts under the receiving department, never twice), as one database aggregate.
     */
    @Query("SELECT COALESCE(SUM(COALESCE(t.estimatedDuration, 0)), 0) FROM Assignment a JOIN a.task t WHERE t.status IN :statuses AND ("
            + "EXISTS (SELECT sp.id FROM com.hotel.workload.support.SupportAssignment sp WHERE sp.taskId = t.id AND sp.status = com.hotel.workload.support.SupportStatus.ACTIVE AND sp.receivingDepartmentCode = :code) "
            + "OR (a.staff.department = :department AND NOT EXISTS (SELECT sp2.id FROM com.hotel.workload.support.SupportAssignment sp2 WHERE sp2.taskId = t.id AND sp2.status = com.hotel.workload.support.SupportStatus.ACTIVE)))")
    long sumSupportAttributedMinutes(@Param("department") com.hotel.workload.staff.Department department, @Param("code") String code,
                                     @Param("statuses") List<TaskStatus> statuses);

    /** The ids of staff who hold at least one active assignment (ids only, no assignment or task detail). */
    @Query("SELECT DISTINCT a.staff.id FROM Assignment a WHERE a.task.status IN :statuses")
    List<UUID> findBusyStaffIds(@Param("statuses") List<TaskStatus> statuses);
}
