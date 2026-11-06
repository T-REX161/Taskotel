package com.hotel.workload.workload;

import com.hotel.workload.staff.Department;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The receiver-scoped entry point of the pure engine, with no database: it reports only the receiving department and defends against stray input. */
public class WorkloadRecommendationReceiverEngineTest {

    private final LocalDate today = LocalDate.of(2026, 11, 6);
    private final LocalDateTime now = today.atStartOfDay();

    private static Task task(String title, Department department) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setDepartment(department);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(20);
        return task;
    }

    @Test
    @DisplayName("Only the receiving department is reported, and a task of another department passed in by mistake is ignored")
    public void onlyTheReceiverIsReported() {
        Department receiver = new Department("RCV", "Receiver");
        Department other = new Department("OTH", "Other");

        WorkloadRecommendationsDto result = WorkloadRecommendationEngine.recommendForReceiver(receiver, List.of(receiver, other), List.of(),
                List.of(task("receiver task", receiver), task("other department task", other)), Map.of("OTH", 5), Set.of(), today, now);

        assertEquals(0, result.recommendations().size());
        assertEquals(1, result.unsupported().size(), "no helper exists, so the receiver is reported as unsupported, and only it");
        assertEquals("RCV", result.unsupported().get(0).departmentCode());
        assertEquals(1, result.unsupported().get(0).pressure().waitingTasks(), "the other department's task is not counted");
        assertFalse(result.toString().contains("other department task"));
        assertFalse(result.limitations().toString().contains("no department"));
    }

    @Test
    @DisplayName("A receiver that is unknown or not active yields an empty result, never another department")
    public void unknownReceiverYieldsNothing() {
        Department inactive = new Department("OFF", "Closed");
        inactive.setActive(false);
        Department other = new Department("OTH", "Other");

        WorkloadRecommendationsDto result = WorkloadRecommendationEngine.recommendForReceiver(inactive, List.of(inactive, other), List.of(),
                List.of(task("t", inactive)), Map.of("OTH", 3), Set.of(), today, now);

        assertTrue(result.recommendations().isEmpty());
        assertTrue(result.unsupported().isEmpty());
    }
}
