package com.hotel.workload.support;

import java.util.List;
import java.util.UUID;

/**
 * Manager-controlled temporary cross-department support: pick available staff from other departments, choose which
 * waiting task of the overloaded department each will help with, preview the workload impact, then confirm.
 */
public interface SupportAssignmentService {

    /** Everything needed to build a plan for this receiving department: its waiting tasks and the staff who could help. */
    SupportDtos.Options getOptions(String receivingDepartmentCode);

    /** Validates a plan against the current data and shows its effect on both departments. Changes nothing. */
    SupportDtos.Preview preview(SupportPlanRequest request);

    /**
     * Revalidates the whole plan on the current data (under row locks) and, only if every rule still holds, creates the
     * assignments. Either all pairs are created or none.
     */
    List<SupportDtos.SupportAssignmentDto> confirm(SupportPlanRequest request);

    /** Support history, newest first. {@code status} null means all. */
    List<SupportDtos.SupportAssignmentDto> list(SupportStatus status);

    /**
     * Ends one active support by hand. A task that has not been started goes back to its department's waiting list
     * (the support's assignment is released); a task already in progress is refused. The record is kept as history.
     */
    SupportDtos.SupportAssignmentDto end(UUID supportId);

    /**
     * Removes one ended (completed or cancelled) record from the history list. It is archived, not deleted: the row, its
     * names and its dates stay in the database. Active support is refused. Touches nothing else.
     */
    SupportDtos.SupportAssignmentDto archive(UUID supportId);
}
