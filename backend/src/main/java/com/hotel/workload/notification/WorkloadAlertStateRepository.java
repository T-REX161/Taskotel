package com.hotel.workload.notification;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WorkloadAlertStateRepository extends JpaRepository<WorkloadAlertState, UUID> {

    /** The state row, locked for the rest of the transaction (SELECT ... FOR UPDATE) with a short wait. It must already exist. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT s FROM WorkloadAlertState s WHERE s.staffId = :staffId AND s.audience = :audience")
    Optional<WorkloadAlertState> lockByStaffIdAndAudience(@Param("staffId") UUID staffId, @Param("audience") String audience);

    Optional<WorkloadAlertState> findByStaffIdAndAudience(UUID staffId, String audience);
}
