package com.hotel.workload.support;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportAssignmentRepository extends JpaRepository<SupportAssignment, UUID> {
    List<SupportAssignment> findByStatus(SupportStatus status);

    List<SupportAssignment> findAllByOrderByCreatedAtDesc();

    List<SupportAssignment> findByStatusOrderByCreatedAtDesc(SupportStatus status);

    /** The history as shown to managers: archived records are kept in the database but not listed. */
    List<SupportAssignment> findAllByArchivedAtIsNullOrderByCreatedAtDesc();

    List<SupportAssignment> findByStatusAndArchivedAtIsNullOrderByCreatedAtDesc(SupportStatus status);

    /**
     * The history a department's Manager may see: records not archived where the department is the donor (home) or the receiver, filtered
     * in the database so no other department's record is ever loaded.
     */
    @Query("SELECT s FROM SupportAssignment s WHERE s.archivedAt IS NULL AND (s.homeDepartmentCode = :code OR s.receivingDepartmentCode = :code) ORDER BY s.createdAt DESC")
    List<SupportAssignment> findHistoryInvolvingDepartment(@Param("code") String code);

    @Query("SELECT s FROM SupportAssignment s WHERE s.archivedAt IS NULL AND s.status = :status AND (s.homeDepartmentCode = :code OR s.receivingDepartmentCode = :code) ORDER BY s.createdAt DESC")
    List<SupportAssignment> findHistoryInvolvingDepartmentAndStatus(@Param("code") String code, @Param("status") SupportStatus status);

    /** The records of one status that involve the department as donor or receiver (a Manager's own records), selected in the database. */
    @Query("SELECT s FROM SupportAssignment s WHERE s.status = :status AND (s.homeDepartmentCode = :code OR s.receivingDepartmentCode = :code)")
    List<SupportAssignment> findByStatusInvolvingDepartment(@Param("status") SupportStatus status, @Param("code") String code);

    Optional<SupportAssignment> findByTaskIdAndStatus(UUID taskId, SupportStatus status);

    boolean existsByTaskId(UUID taskId);

    /** True when this account approved any support record (history that deleting the account would orphan). */
    boolean existsByApprovedByUserId(UUID userId);

    /** The task of this record, only when the record involves the department as donor or receiver (a Manager's own records). */
    @Query("SELECT s.taskId FROM SupportAssignment s WHERE s.id = :id AND (s.homeDepartmentCode = :code OR s.receivingDepartmentCode = :code)")
    Optional<UUID> findTaskIdByIdInvolvingDepartment(@Param("id") UUID id, @Param("code") String code);

    /**
     * Loads the record fresh and locks it until the transaction ends, only when it involves the department as donor or receiver. A record of
     * any other department is never loaded or locked, and answers exactly like one that does not exist.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SupportAssignment s WHERE s.id = :id AND (s.homeDepartmentCode = :code OR s.receivingDepartmentCode = :code)")
    Optional<SupportAssignment> lockByIdInvolvingDepartment(@Param("id") UUID id, @Param("code") String code);

    /** The task of this support record, without loading (and caching) the record itself. */
    @Query("SELECT s.taskId FROM SupportAssignment s WHERE s.id = :id")
    Optional<UUID> findTaskIdById(@Param("id") UUID id);

    /** Loads the record fresh and locks it until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SupportAssignment s WHERE s.id = :id")
    Optional<SupportAssignment> lockById(@Param("id") UUID id);
}
