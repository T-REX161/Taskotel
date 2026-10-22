package com.hotel.workload.dashboard;

import com.hotel.workload.assignment.AssignmentDto;
import com.hotel.workload.workload.WorkloadDto;

import java.util.List;

public class StaffDashboardSummaryDto {
    
    private WorkloadDto workload;
    
    // Detailed counts for their own tasks
    private long pendingTasks;
    private long assignedTasks;
    private long inProgressTasks;
    private long completedTasks;
    
    // A list of their current assignments
    private List<AssignmentDto> recentAssignments;

    public WorkloadDto getWorkload() { return workload; }
    public void setWorkload(WorkloadDto workload) { this.workload = workload; }
    
    public long getPendingTasks() { return pendingTasks; }
    public void setPendingTasks(long pendingTasks) { this.pendingTasks = pendingTasks; }
    
    public long getAssignedTasks() { return assignedTasks; }
    public void setAssignedTasks(long assignedTasks) { this.assignedTasks = assignedTasks; }
    
    public long getInProgressTasks() { return inProgressTasks; }
    public void setInProgressTasks(long inProgressTasks) { this.inProgressTasks = inProgressTasks; }
    
    public long getCompletedTasks() { return completedTasks; }
    public void setCompletedTasks(long completedTasks) { this.completedTasks = completedTasks; }
    
    public List<AssignmentDto> getRecentAssignments() { return recentAssignments; }
    public void setRecentAssignments(List<AssignmentDto> recentAssignments) { this.recentAssignments = recentAssignments; }
}
