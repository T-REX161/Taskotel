package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.SupportAssignmentDto;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not transactional on purpose: these tests really commit and race several requests, then clean up after themselves.
 */
@SpringBootTest
public class SupportConcurrencyTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;

    private SupportTestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
    }

    @AfterEach
    void cleanUp() {
        SupportTestFixture.signOut();
        fx.cleanUp();
    }

    /** Runs every plan at the same moment, each as its own manager request; returns how many were confirmed. */
    private List<Boolean> race(List<SupportPlanRequest> plans) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(plans.size());
        CountDownLatch ready = new CountDownLatch(plans.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (SupportPlanRequest plan : plans) {
            Callable<Boolean> call = () -> {
                SupportTestFixture.signInAs("racer", "MANAGER");
                ready.countDown();
                go.await();
                try {
                    service.confirm(plan);
                    return true;
                } catch (SupportPlanException e) {
                    assertEquals(409, e.getStatus().value(), "a loser gets a clean 409");
                    return false;
                } finally {
                    SupportTestFixture.signOut();
                }
            };
            results.add(pool.submit(call));
        }
        ready.await();
        go.countDown();
        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> f : results) outcomes.add(f.get());
        pool.shutdown();
        return outcomes;
    }

    @Test
    @DisplayName("Six managers confirming the same plan at once: exactly one wins, the rest get a clean 409")
    void identicalPlansOnlyOneWins() throws Exception {
        var s = fx.overloaded(2, 4);
        List<SupportPlanRequest> plans = new ArrayList<>();
        for (int i = 0; i < 6; i++) plans.add(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)));

        List<Boolean> outcomes = race(plans);

        assertEquals(1, outcomes.stream().filter(b -> b).count(), outcomes.toString());
        assertEquals(1, supportRepository.findAll().stream().filter(x -> x.getTaskId().equals(s.tasks.get(0).getId())).count());
        assertEquals(1, assignmentRepository.findAllByTaskId(s.tasks.get(0).getId()).size());
    }

    @Test
    @DisplayName("Two different plans that are each valid but together would empty the donor: only one is confirmed")
    void donorReserveHoldsUnderConcurrency() throws Exception {
        var s = fx.overloaded(2, 2);      // the donor has two free people: it may lend one, never both
        Staff first = s.donors.get(0), second = s.donors.get(1);
        Task t1 = s.tasks.get(0), t2 = s.tasks.get(1);

        List<Boolean> outcomes = race(List.of(fx.plan(s.receiving, first, t1), fx.plan(s.receiving, second, t2)));

        assertEquals(1, outcomes.stream().filter(b -> b).count(), "exactly one plan wins: " + outcomes);
        long lent = supportRepository.findAll().stream().filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())).count();
        assertEquals(1, lent);
        // the donor department still has a free person
        long stillFree = s.donors.stream().filter(d -> assignmentRepository.findByStaffId(d.getId()).isEmpty()).count();
        assertTrue(stillFree >= 1, "the donor kept a free person");
    }

    @Test
    @DisplayName("Two plans that want the same person for different tasks: one wins and nobody gets two tasks")
    void samePersonCannotBeBookedTwice() throws Exception {
        var s = fx.overloaded(2, 4);
        Staff helper = s.donors.get(0);

        List<Boolean> outcomes = race(List.of(fx.plan(s.receiving, helper, s.tasks.get(0)), fx.plan(s.receiving, helper, s.tasks.get(1))));

        assertEquals(1, outcomes.stream().filter(b -> b).count(), outcomes.toString());
        assertEquals(1, assignmentRepository.findByStaffId(helper.getId()).size(), "one active task for the person");
    }

    @Test
    @DisplayName("Six managers ending the same support at once: exactly one ends it, the rest get a clean 409, the task goes back to waiting once")
    void endingTheSameSupportOnlyOneWins() throws Exception {
        var s = fx.overloaded(2, 3);
        SupportTestFixture.signInAs("setup", "MANAGER");
        SupportAssignmentDto created = service.confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).get(0);
        SupportTestFixture.signOut();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                SupportTestFixture.signInAs("racer", "MANAGER");
                ready.countDown();
                go.await();
                try {
                    service.end(created.id());
                    return true;
                } catch (SupportPlanException e) {
                    assertEquals(409, e.getStatus().value(), "a loser gets a clean 409");
                    return false;
                } finally {
                    SupportTestFixture.signOut();
                }
            }));
        }
        ready.await();
        go.countDown();
        long wins = 0;
        for (Future<Boolean> f : results) if (f.get()) wins++;
        pool.shutdown();

        assertEquals(1, wins, "exactly one request ends it");
        SupportAssignment stored = supportRepository.findById(created.id()).orElseThrow();
        assertEquals(SupportStatus.CANCELLED, stored.getStatus());
        assertEquals(SupportEndReason.MANUALLY_ENDED, stored.getEndReason());
        assertEquals(0, assignmentRepository.findAllByTaskId(s.tasks.get(0).getId()).size());
        assertEquals(com.hotel.workload.task.TaskStatus.PENDING, taskRepository.findById(s.tasks.get(0).getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Without the acknowledgement the reserve still holds under concurrency; with it, two managers may both lend (each saw the warning)")
    void reserveOverrideUnderConcurrency() throws Exception {
        var s = fx.overloaded(2, 2);      // the donor has two free people
        SupportPlanRequest a = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));
        SupportPlanRequest b = fx.plan(s.receiving, s.donors.get(1), s.tasks.get(1));
        a.setAcknowledgeReserveOverride(true);
        b.setAcknowledgeReserveOverride(true);

        List<Boolean> outcomes = race(List.of(a, b));

        assertEquals(2, outcomes.stream().filter(x -> x).count(), "both acknowledged, both allowed: " + outcomes);
        long lent = supportRepository.findAll().stream().filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())).count();
        assertEquals(2, lent);
        assertEquals(0, s.donors.stream().filter(d -> assignmentRepository.findByStaffId(d.getId()).isEmpty()).count(), "the donor is left with no free staff, as acknowledged");
    }

    @Test
    @DisplayName("Two acknowledged plans for the same last free person: still only one wins and nobody gets two tasks")
    void acknowledgedPlansForTheSamePersonStillConflict() throws Exception {
        var s = fx.overloaded(2, 1);
        SupportPlanRequest a = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));
        SupportPlanRequest b = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(1));
        a.setAcknowledgeReserveOverride(true);
        b.setAcknowledgeReserveOverride(true);

        List<Boolean> outcomes = race(List.of(a, b));

        assertEquals(1, outcomes.stream().filter(x -> x).count(), outcomes.toString());
        assertEquals(1, assignmentRepository.findByStaffId(s.donors.get(0).getId()).size());
    }

    @Test
    @DisplayName("Two plans for the same task from different donors: one wins")
    void sameTaskCannotBeSupportedTwice() throws Exception {
        var s = fx.overloaded(2, 4);

        List<Boolean> outcomes = race(List.of(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)), fx.plan(s.receiving, s.donors.get(1), s.tasks.get(0))));

        assertEquals(1, outcomes.stream().filter(b -> b).count(), outcomes.toString());
        assertEquals(1, assignmentRepository.findAllByTaskId(s.tasks.get(0).getId()).size());
    }
}
