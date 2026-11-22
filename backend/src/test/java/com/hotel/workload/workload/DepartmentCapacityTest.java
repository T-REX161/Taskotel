package com.hotel.workload.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The capacity model as pure arithmetic: one eligible person is one standard shift of 480 minutes. Every comparison is exact (no rounding). */
class DepartmentCapacityTest {

    private static final DepartmentCapacity.Risk LIMIT_85 = DepartmentCapacity.Risk.of(true, 85);

    @Test
    @DisplayName("Capacity is people x the standard 480-minute shift; nobody is no capacity")
    void capacityIsPeopleTimesTheShift() {
        assertEquals(480, DepartmentCapacity.STANDARD_SHIFT_MINUTES);
        assertEquals(0, DepartmentCapacity.capacityMinutes(0));
        assertEquals(0, DepartmentCapacity.capacityMinutes(-3));
        assertEquals(480, DepartmentCapacity.capacityMinutes(1));
        assertEquals(1440, DepartmentCapacity.capacityMinutes(3));
    }

    @Test
    @DisplayName("Worked example: 1000 minutes on 2 people is 104.2%; one helper makes it 69.4%; the minutes are still 1000")
    void helperLowersUtilizationNotMinutes() {
        long minutes = 1000;
        assertEquals(104.2, DepartmentCapacity.utilizationPercent(minutes, 2));
        assertEquals(69.4, DepartmentCapacity.utilizationPercent(minutes, 3));
        assertEquals(52.1, DepartmentCapacity.utilizationPercent(minutes, 4));
        assertTrue(DepartmentCapacity.atRisk(minutes, 2, LIMIT_85));
        assertFalse(DepartmentCapacity.atRisk(minutes, 3, LIMIT_85));
        // The capacity scales exactly with people: 500 / (2 x 480) = 52.1%, and a third person gives 500 / 1440 = 34.7%
        assertEquals(52.1, DepartmentCapacity.utilizationPercent(500, 2));
        assertEquals(34.7, DepartmentCapacity.utilizationPercent(500, 3));
    }

    @Test
    @DisplayName("The threshold comparison is exact: 816 of 960 minutes is exactly 85%, 815 is not, and display rounding never decides")
    void comparisonIsExact() {
        assertTrue(DepartmentCapacity.atOrAbove(816, 2, 85), "816 x 100 = 81600 = 85 x 960");
        assertFalse(DepartmentCapacity.atOrAbove(815, 2, 85));
        assertEquals(85.0, DepartmentCapacity.utilizationPercent(816, 2));
        assertEquals(84.9, DepartmentCapacity.utilizationPercent(815, 2));
        // 959 of 960 displays as 99.9% and is below 100; 960 of 960 is 100
        assertFalse(DepartmentCapacity.atOrAbove(959, 2, 100));
        assertTrue(DepartmentCapacity.atOrAbove(960, 2, 100));
        // the smallest threshold
        assertTrue(DepartmentCapacity.atOrAbove(5, 1, 1), "5 / 480 = 1.04%");
        assertFalse(DepartmentCapacity.atOrAbove(4, 1, 1), "4 / 480 = 0.83%");
        assertFalse(DepartmentCapacity.atOrAbove(0, 1, 1));
    }

    @Test
    @DisplayName("Levels: AT_RISK from the threshold, otherwise LOW up to 25%, NORMAL up to 50%, HIGH above; with the alert off nothing is AT_RISK")
    void levels() {
        // 4 people = 1920 minutes: 25% = 480, 50% = 960
        assertEquals(WorkloadStatus.LOW, DepartmentCapacity.level(480, 4, LIMIT_85));
        assertEquals(WorkloadStatus.NORMAL, DepartmentCapacity.level(481, 4, LIMIT_85));
        assertEquals(WorkloadStatus.NORMAL, DepartmentCapacity.level(960, 4, LIMIT_85));
        assertEquals(WorkloadStatus.HIGH, DepartmentCapacity.level(961, 4, LIMIT_85));
        assertEquals(WorkloadStatus.HIGH, DepartmentCapacity.level(1631, 4, LIMIT_85), "1631 / 1920 = 84.9%");
        assertEquals(WorkloadStatus.AT_RISK, DepartmentCapacity.level(1632, 4, LIMIT_85), "1632 / 1920 = 85.0%");
        // a low threshold overrides the bands: at 10% anything from 10% up is AT RISK
        assertEquals(WorkloadStatus.AT_RISK, DepartmentCapacity.level(192, 4, DepartmentCapacity.Risk.of(true, 10)));
        assertEquals(WorkloadStatus.LOW, DepartmentCapacity.level(191, 4, DepartmentCapacity.Risk.of(true, 10)));
        // alert off: the same loads are just HIGH
        assertEquals(WorkloadStatus.HIGH, DepartmentCapacity.level(1920, 4, DepartmentCapacity.Risk.OFF));
        assertEquals(WorkloadStatus.HIGH, DepartmentCapacity.level(5000, 4, DepartmentCapacity.Risk.of(false, 1)));
    }

    @Test
    @DisplayName("No capacity: work with nobody to do it is AT RISK (alert on) with no percentage; no work and nobody is LOW; never a division by zero")
    void noCapacity() {
        assertTrue(DepartmentCapacity.noCapacity(60, 0));
        assertFalse(DepartmentCapacity.noCapacity(0, 0));
        assertFalse(DepartmentCapacity.noCapacity(60, 1));
        assertNull(DepartmentCapacity.utilizationPercent(60, 0));
        assertEquals(0.0, DepartmentCapacity.utilizationPercent(0, 0));
        assertEquals(WorkloadStatus.AT_RISK, DepartmentCapacity.level(60, 0, LIMIT_85));
        assertEquals(WorkloadStatus.AT_RISK, DepartmentCapacity.level(60, 0, DepartmentCapacity.Risk.of(true, 100)), "whatever the threshold");
        assertEquals(WorkloadStatus.HIGH, DepartmentCapacity.level(60, 0, DepartmentCapacity.Risk.OFF), "alert off: not flagged as risk, but not fine either");
        assertEquals(WorkloadStatus.LOW, DepartmentCapacity.level(0, 0, LIMIT_85));
        assertFalse(DepartmentCapacity.atRisk(0, 0, LIMIT_85));
    }

    @Test
    @DisplayName("Helpers needed to leave risk: the smallest number of extra people that brings utilization below the threshold")
    void helpersNeeded() {
        assertEquals(0, DepartmentCapacity.helpersToLeaveRisk(500, 2, LIMIT_85), "not at risk: none");
        assertEquals(1, DepartmentCapacity.helpersToLeaveRisk(1000, 2, LIMIT_85), "1000 / 1440 = 69.4%");
        assertEquals(3, DepartmentCapacity.helpersToLeaveRisk(2000, 2, LIMIT_85), "2000 / 2400 = 83.3%, but 2000 / 1920 = 104.2% is not enough");
        assertEquals(1, DepartmentCapacity.helpersToLeaveRisk(816, 2, LIMIT_85), "at exactly 85.0% one more person is needed");
        assertEquals(1, DepartmentCapacity.helpersToLeaveRisk(60, 0, LIMIT_85), "nobody at all: one person brings it to 12.5%");
        assertEquals(0, DepartmentCapacity.helpersToLeaveRisk(5000, 1, DepartmentCapacity.Risk.OFF), "alert off: nothing to leave");
    }

    @Test
    @DisplayName("Donor limit: the donor must stay below the threshold (alert on) or within a full shift per person (alert off) after lending")
    void donorLimit() {
        assertFalse(DepartmentCapacity.exceedsLimit(815, 2, LIMIT_85));
        assertTrue(DepartmentCapacity.exceedsLimit(816, 2, LIMIT_85), "at the limit counts as past it");
        assertFalse(DepartmentCapacity.exceedsLimit(960, 2, DepartmentCapacity.Risk.OFF), "exactly a full shift each is allowed with no threshold");
        assertTrue(DepartmentCapacity.exceedsLimit(961, 2, DepartmentCapacity.Risk.OFF), "more than a full shift each would only move the overload");
        assertTrue(DepartmentCapacity.exceedsLimit(100, 0, LIMIT_85), "work and nobody left to do it");
        assertFalse(DepartmentCapacity.exceedsLimit(0, 0, LIMIT_85), "no work: lending the last person harms nothing");
    }
}
