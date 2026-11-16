package com.hotel.workload.staff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Read and append only: nothing in the application updates or deletes an audit record. */
public interface ManagerViewGrantChangeRepository extends JpaRepository<ManagerViewGrantChange, UUID> {

    List<ManagerViewGrantChange> findByManagerUserIdOrderByChangedAtDesc(UUID managerUserId);

    /** One page of a Manager's records, optionally narrowed to one action and/or one department (null = any). Order from the Pageable. */
    @Query("select c from ManagerViewGrantChange c where c.managerUserId = :managerUserId"
            + " and (:action is null or c.action = :action)"
            + " and (:departmentCode is null or c.departmentCode = :departmentCode)")
    Page<ManagerViewGrantChange> findPage(@Param("managerUserId") UUID managerUserId, @Param("action") String action,
                                          @Param("departmentCode") String departmentCode, Pageable pageable);

    /** [action, count] for a Manager's records, optionally within one department. */
    @Query("select c.action, count(c) from ManagerViewGrantChange c where c.managerUserId = :managerUserId"
            + " and (:departmentCode is null or c.departmentCode = :departmentCode) group by c.action")
    List<Object[]> countByAction(@Param("managerUserId") UUID managerUserId, @Param("departmentCode") String departmentCode);

    /** [departmentCode, departmentName] for every department that appears in a Manager's records (one name per code). */
    @Query("select c.departmentCode, max(c.departmentName) from ManagerViewGrantChange c where c.managerUserId = :managerUserId"
            + " group by c.departmentCode")
    List<Object[]> departmentsInHistory(@Param("managerUserId") UUID managerUserId);
}
