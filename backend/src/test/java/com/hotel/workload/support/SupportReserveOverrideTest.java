package com.hotel.workload.support;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.*;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The manager override of the donor reserve: a department may lend its last free person only after the risk has been
 * acknowledged. The acknowledgement relaxes that one rule and nothing else, and confirms nothing by itself.
 */
@SpringBootTest
@Transactional
public class SupportReserveOverrideTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private EntityManager entityManager;

    private SupportTestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        SupportTestFixture.signInAs("manager_test", "MANAGER");
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
    }

    private SupportPlanRequest acknowledged(SupportPlanRequest request) {
        request.setAcknowledgeReserveOverride(true);
        return request;
    }

    private Set<String> codes(List<Problem> problems) {
        return problems.stream().map(Problem::code).collect(Collectors.toSet());
    }

    private DonorDepartment donorOf(SupportTestFixture.Scenario s) {
        return service.getOptions(s.receiving.getCode()).donors().stream().filter(d -> d.code().equals(s.donor.getCode())).findFirst().orElseThrow();
    }

    private long supportCount(SupportTestFixture.Scenario s) {
        return supportRepository.findAll().stream().filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())).count();
    }

    // ── the last free person ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A donor's only free person is offered: it cannot lend under the normal rule but could with an acknowledgement")
    void optionsShowTheOverrideCapacity() {
        var s = fx.overloaded(2, 1);
        DonorDepartment donor = donorOf(s);
        assertEquals(1, donor.freeStaff());
        assertEquals(0, donor.maxLendable(), "the normal rule keeps the last free person");
        assertFalse(donor.canLend());
        assertEquals(1, donor.overrideLendable(), "with an acknowledgement the last free person could go");
        assertEquals(1, donor.staff().size(), "and the person is listed, so the manager can choose them");
    }

    @Test
    @DisplayName("Without the acknowledgement the last free person is refused, and the preview says an acknowledgement would allow it")
    void refusedWithoutAcknowledgement() {
        var s = fx.overloaded(2, 1);
        SupportPlanRequest plan = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));

        Preview preview = service.preview(plan);

        assertFalse(preview.valid());
        assertTrue(codes(preview.problems()).contains("DONOR_RESERVE"));
        assertTrue(preview.canOverrideReserve(), "only the reserve blocks it, so acknowledging could allow it");
        assertTrue(preview.warnings().isEmpty());
        SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(plan));
        assertEquals(409, ex.getStatus().value());
        assertTrue(codes(ex.getProblems()).contains("DONOR_RESERVE"));
        assertEquals(0, supportCount(s));
    }

    @Test
    @DisplayName("With the acknowledgement the preview is valid, names the donor and how many free staff remain, and still creates nothing")
    void previewWithAcknowledgementWarnsAndChangesNothing() {
        var s = fx.overloaded(2, 1);

        Preview preview = service.preview(acknowledged(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))));

        assertTrue(preview.valid());
        assertFalse(preview.canOverrideReserve());
        assertEquals(1, preview.warnings().size());
        Problem warning = preview.warnings().get(0);
        assertEquals("RESERVE_OVERRIDE", warning.code());
        assertTrue(warning.message().contains(s.donor.getName()) && warning.message().contains("0 free staff"), warning.message());
        DepartmentImpact donor = preview.donors().get(0);
        assertEquals(1, donor.before().freeStaff());
        assertEquals(0, donor.after().freeStaff(), "the donor is left with no free staff");
        assertEquals(1, donor.reserveRequired());
        assertEquals(0, supportCount(s), "a preview, even an acknowledged one, creates nothing");
    }

    @Test
    @DisplayName("Confirming an acknowledged plan works, reports the override, keeps the permanent department, and later reads do not claim it")
    void confirmWithAcknowledgement() {
        var s = fx.overloaded(2, 1);
        Staff helper = s.donors.get(0);

        List<SupportAssignmentDto> created = service.confirm(acknowledged(fx.plan(s.receiving, helper, s.tasks.get(0))));

        assertEquals(1, created.size());
        assertEquals(Boolean.TRUE, created.get(0).reserveOverride(), "the confirmation result exposes the override");
        entityManager.flush();
        entityManager.clear();
        assertEquals(s.donor.getCode(), staffRepository.findById(helper.getId()).orElseThrow().getDepartment().getCode(), "the permanent department never changes");
        assertEquals(s.donor.getCode(), created.get(0).homeDepartmentCode());
        assertNull(service.list(null).stream().filter(r -> r.id().equals(created.get(0).id())).findFirst().orElseThrow().reserveOverride(),
                "it is not stored, so later reads do not claim it (a persistent audit would need a schema change)");
        assertTrue(service.getOptions(s.receiving.getCode()).donors().stream().noneMatch(d -> d.code().equals(s.donor.getCode())),
                "the donor has nobody left to lend, so it is no longer offered");
    }

    // ── normal borrowing is unaffected ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("When a reserve remains there is no warning and no override, with or without the acknowledgement")
    void normalBorrowingIsUnchanged() {
        var s = fx.overloaded(2, 3);

        Preview plain = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)));
        Preview acked = service.preview(acknowledged(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))));

        assertTrue(plain.valid() && plain.warnings().isEmpty() && !plain.canOverrideReserve());
        assertTrue(acked.valid() && acked.warnings().isEmpty(), "an acknowledgement that is not needed adds no warning");

        List<SupportAssignmentDto> created = service.confirm(acknowledged(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))));
        assertEquals(Boolean.FALSE, created.get(0).reserveOverride(), "not an override: the donor still has free people");
        assertEquals(2, donorOf(s).freeStaff());
    }

    @Test
    @DisplayName("The second loan that would empty a donor needs the acknowledgement, and then leaves exactly the stated numbers")
    void secondLoanNeedsTheAcknowledgement() {
        var s = fx.overloaded(2, 2);
        Staff a = s.donors.get(0), b = s.donors.get(1);
        service.confirm(fx.plan(s.receiving, a, s.tasks.get(0)));      // 2 -> 1 free: fine
        entityManager.flush();
        entityManager.clear();

        Preview second = service.preview(fx.plan(s.receiving, b, s.tasks.get(1)));
        assertFalse(second.valid());
        assertTrue(second.canOverrideReserve());
        Preview ack = service.preview(acknowledged(fx.plan(s.receiving, b, s.tasks.get(1))));
        assertTrue(ack.valid());
        assertEquals(1, ack.donors().get(0).before().freeStaff());
        assertEquals(0, ack.donors().get(0).after().freeStaff());
    }

    // ── what the acknowledgement does not relax ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The donor's own waiting work is a hard floor: even acknowledged, its only free person is not lent while it has waiting tasks")
    void ownWaitingWorkIsAHardFloor() {
        var s = fx.overloaded(2, 1);
        fx.waiting("Donor's own waiting task", s.donor, Priority.MEDIUM, 30);
        entityManager.flush();

        Preview plain = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)));
        Preview acked = service.preview(acknowledged(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))));

        assertFalse(plain.canOverrideReserve(), "an acknowledgement cannot help here, so it is not offered");
        assertFalse(acked.valid());
        assertTrue(codes(acked.problems()).contains("DONOR_RESERVE"));
        assertFalse(acked.canOverrideReserve());
        assertTrue(acked.warnings().isEmpty());
        assertThrows(SupportPlanException.class, () -> service.confirm(acknowledged(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)))));
        assertEquals(0, donorOf(s).overrideLendable(), "and the options do not offer it either");
    }

    @Test
    @DisplayName("Every other eligibility rule still applies under the acknowledgement")
    void otherRulesStillApply() {
        var s = fx.overloaded(3, 1);
        Staff unverified = fx.person("Unverified Una", s.donor, Role.STAFF, false, true, true, Availability.AVAILABLE, true);
        Staff unavailable = fx.person("Away Alf", s.donor, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        Staff inactive = fx.person("Inactive Ida", s.donor, Role.STAFF, true, false, true, Availability.AVAILABLE, false);
        Staff manager = fx.person("Manager Max", s.donor, Role.MANAGER, true, false, true, Availability.AVAILABLE, true);
        Staff local = fx.eligible("Local Lena", s.receiving);
        Staff busy = fx.eligible("Busy Bo", s.donor);
        fx.assign(fx.task("Busy Bo's task", s.donor, Priority.LOW, 20, TaskStatus.PENDING), busy);
        Task done = fx.task("Done", s.receiving, Priority.LOW, 10, TaskStatus.COMPLETED);
        entityManager.flush();

        Object[][] cases = {{unverified, "CANNOT_SIGN_IN"}, {unavailable, "STAFF_UNAVAILABLE"}, {inactive, "STAFF_INACTIVE"},
                {manager, "NOT_STAFF_ROLE"}, {local, "SAME_DEPARTMENT"}, {busy, "STAFF_BUSY"}};
        for (Object[] c : cases) {
            Staff person = (Staff) c[0];
            Preview p = service.preview(acknowledged(fx.plan(s.receiving, person, s.tasks.get(0))));
            assertFalse(p.valid(), person.getName());
            assertTrue(codes(p.problems()).contains((String) c[1]), person.getName() + " -> " + codes(p.problems()));
        }
        Preview finishedTask = service.preview(acknowledged(fx.plan(s.receiving, s.donors.get(0), done)));
        assertTrue(codes(finishedTask.problems()).contains("TASK_FINISHED"));
        assertFalse(finishedTask.valid());
    }

    // ── stale and concurrent changes ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("If the person stops being available between preview and confirm, the confirmation is refused as out of date")
    void availabilityChangeMakesThePlanStale() {
        var s = fx.overloaded(2, 1);
        Staff helper = s.donors.get(0);
        SupportPlanRequest plan = acknowledged(fx.plan(s.receiving, helper, s.tasks.get(0)));
        Preview preview = service.preview(plan);
        assertTrue(preview.valid());
        plan.setFingerprint(preview.fingerprint());

        helper.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(helper);
        entityManager.clear();

        SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(plan));
        assertEquals(409, ex.getStatus().value());
        assertTrue(codes(ex.getProblems()).contains("STALE_PLAN"));
        assertEquals(0, supportCount(s));
        assertFalse(service.preview(acknowledged(fx.plan(s.receiving, helper, s.tasks.get(0)))).valid(), "and a fresh preview now explains why");
    }

    @Test
    @DisplayName("If another free person leaves the donor meanwhile, the plan is revalidated and the warning situation is recalculated")
    void aReserveThatShrinksIsRevalidated() {
        var s = fx.overloaded(2, 2);
        Staff a = s.donors.get(0), b = s.donors.get(1);
        SupportPlanRequest plan = fx.plan(s.receiving, a, s.tasks.get(0));
        Preview before = service.preview(plan);
        assertTrue(before.valid() && before.warnings().isEmpty(), "no warning while the donor has two free");
        plan.setFingerprint(before.fingerprint());

        fx.assign(fx.waiting("Taken", s.donor, Priority.LOW, 15), b);      // someone else takes the donor's other free person
        entityManager.flush();
        entityManager.clear();

        SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(plan));
        assertEquals(409, ex.getStatus().value());
        assertTrue(codes(ex.getProblems()).contains("STALE_PLAN") || codes(ex.getProblems()).contains("DONOR_RESERVE"), codes(ex.getProblems()).toString());
        Preview now = service.preview(fx.plan(s.receiving, a, s.tasks.get(0)));
        assertFalse(now.valid(), "the donor is now down to its last free person");
        assertTrue(codes(now.problems()).contains("DONOR_RESERVE"));
        assertTrue(now.canOverrideReserve(), "which a manager can acknowledge");
        Preview acked = service.preview(acknowledged(fx.plan(s.receiving, a, s.tasks.get(0))));
        assertTrue(acked.valid());
        assertEquals("RESERVE_OVERRIDE", acked.warnings().get(0).code(), "the warning appears now, for the new situation");
        assertEquals(0, supportCount(s));
    }

    @Test
    @DisplayName("A plan previewed without the acknowledgement cannot be confirmed with it: the acknowledgement is part of what was reviewed")
    void theAcknowledgementIsPartOfWhatWasPreviewed() {
        var s = fx.overloaded(2, 1);
        String withoutAck = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).fingerprint();
        String withAck = service.preview(acknowledged(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)))).fingerprint();
        assertNotEquals(withoutAck, withAck);

        SupportPlanRequest confirm = acknowledged(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)));
        confirm.setFingerprint(withoutAck);
        SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(confirm));
        assertTrue(codes(ex.getProblems()).contains("STALE_PLAN"), "the manager must review the plan that carries the warning");
        assertEquals(0, supportCount(s));
    }
}
