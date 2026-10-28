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

    Optional<SupportAssignment> findByTaskIdAndStatus(UUID taskId, SupportStatus status);

    boolean existsByTaskId(UUID taskId);

    /** The task of this support record, without loading (and caching) the record itself. */
    @Query("SELECT s.taskId FROM SupportAssignment s WHERE s.id = :id")
    Optional<UUID> findTaskIdById(@Param("id") UUID id);

    /** Loads the record fresh and locks it until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SupportAssignment s WHERE s.id = :id")
    Optional<SupportAssignment> lockById(@Param("id") UUID id);
}
