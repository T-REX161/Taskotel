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

    @Modifying
    @Query("DELETE FROM Assignment a WHERE a.task.id = :taskId")
    void deleteByTaskId(@Param("taskId") UUID taskId);

    @EntityGraph(attributePaths = {"task", "staff"})
    List<Assignment> findAllByOrderByAssignedAtDesc();

    /**
     * Fetch all assignments whose task status is in the supplied set.
     * Used for workload calculation to avoid N+1 queries.
     */
    @Query("SELECT a FROM Assignment a JOIN FETCH a.task t JOIN FETCH a.staff s WHERE t.status IN :statuses")
    List<Assignment> findAllActiveAssignments(@Param("statuses") List<TaskStatus> statuses);

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
}
