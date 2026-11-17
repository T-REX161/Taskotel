package com.hotel.workload.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.hotel.workload.workload.DepartmentWorkloadStatus.*;
import static org.junit.jupiter.api.Assertions.*;

public class DepartmentWorkloadStatusTest {

    // capacity 400 minutes: 25% = 100, 50% = 200, 75% = 300
    @Test
    @DisplayName("Department thresholds: LOW 0-25%, NORMAL above 25% through 50%, HIGH above 50% through 75%, AT_RISK above 75%; each boundary belongs to the lower band")
    public void boundaries() {
        int[] minutes = {0, 100, 101, 200, 201, 300, 301, 400, 1200};
        DepartmentWorkloadStatus[] expected = {LOW, LOW, NORMAL, NORMAL, HIGH, HIGH, AT_RISK, AT_RISK, AT_RISK};
        for (int i = 0; i < minutes.length; i++) assertEquals(expected[i], derive(minutes[i], 400), minutes[i] + " of 400 minutes");
    }

    @Test
    @DisplayName("Exact boundaries are not rounded: a hair above 25% is NORMAL even though it would display as 25.0%")
    public void exactBoundariesAreNotRounded() {
        assertEquals(LOW, derive(2400, 9600));      // exactly 25%
        assertEquals(NORMAL, derive(2401, 9600));   // 25.01%
        assertEquals(HIGH, derive(7200, 9600));     // exactly 75%
        assertEquals(AT_RISK, derive(7201, 9600));  // 75.01%
    }

    @Test
    @DisplayName("No capacity is explicit: zero (or negative) capacity is NO_CAPACITY whatever the assigned minutes, and nothing is divided")
    public void zeroCapacity() {
        assertEquals(NO_CAPACITY, derive(0, 0));
        assertEquals(NO_CAPACITY, derive(500, 0));
        assertEquals(NO_CAPACITY, derive(10, -480));
    }

    @Test
    @DisplayName("The person thresholds are unchanged and separate: 360 minutes is HIGH and 361 is AT_RISK for ONE person")
    public void personThresholdsUnchanged() {
        assertEquals(WorkloadStatus.LOW, WorkloadDto.deriveStatus(120));
        assertEquals(WorkloadStatus.NORMAL, WorkloadDto.deriveStatus(240));
        assertEquals(WorkloadStatus.HIGH, WorkloadDto.deriveStatus(360));
        assertEquals(WorkloadStatus.AT_RISK, WorkloadDto.deriveStatus(361));
    }

    @Test
    @DisplayName("Response contract: the endpoint exposes exactly the fields the Workload page reads (a backend still on an older shape shows every card as zero)")
    public void responseContract() {
        java.util.Set<String> exposed = new java.util.TreeSet<>();
        for (java.lang.reflect.Method m : DepartmentWorkloadDto.class.getDeclaredMethods()) {
            if (m.getName().startsWith("get") && m.getParameterCount() == 0) {
                exposed.add(Character.toLowerCase(m.getName().charAt(3)) + m.getName().substring(4));
            }
        }
        assertEquals(new java.util.TreeSet<>(java.util.List.of("code", "name", "assignedMinutes", "capacityMinutes", "eligibleStaffCount",
                "utilizationPercent", "status", "activeTaskCount", "staffCount", "unassignedTaskCount", "unassignedMinutes")), exposed);
    }

    @Test
    @DisplayName("The DTO derives capacity (480 per eligible staff member), a one-decimal utilization and the status; no capacity gives no utilization")
    public void dtoDerivation() {
        DepartmentWorkloadDto d = new DepartmentWorkloadDto("X", "X", 220, 3, 3, 3, 1, 30);
        assertEquals(1440, d.getCapacityMinutes());
        assertEquals(15.3, d.getUtilizationPercent());
        assertEquals(LOW, d.getStatus());
        DepartmentWorkloadDto none = new DepartmentWorkloadDto("Y", "Y", 90, 0, 1, 1, 0, 0);
        assertEquals(0, none.getCapacityMinutes());
        assertNull(none.getUtilizationPercent());
        assertEquals(NO_CAPACITY, none.getStatus());
    }
}
