package com.hotel.workload.task;

import java.util.List;

/**
 * What a Staff member sees under "Available Tasks". {@code canClaim} is false (with a {@code reason} to show) when the caller may not
 * accept a task right now (marked unavailable, or already holding an active task); the list is then empty. The server decides again on
 * every claim, so this only explains the screen.
 */
public record AvailableTasksDto(boolean canClaim, String reason, List<TaskDto> tasks) {}
