package com.hotel.workload.support;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.*;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.WorkloadRecommendationEngine;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Severity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Temporary cross-department support. The rules are the same ones that produce the read-only recommendations
 * ({@link WorkloadRecommendationEngine}): a lent person must be an active STAFF account that can sign in, free and
 * available, and the lending department must keep at least one free person and enough free staff for its own waiting
 * work. Minutes are the existing workload measure.
 *
 * <p>A confirmed plan creates a normal {@link Assignment} for each pair (so workload, dashboards and the assignment
 * page just work) plus a {@link SupportAssignment} that records the temporary, cross-department nature. The existing
 * same-department rule of the ordinary assignment endpoints is not touched; only this service can create such a pair,
 * after validating everything below. A staff member's permanent department is never written.
 */
@Service
public class SupportAssignmentServiceImpl implements SupportAssignmentService, SupportLifecycle {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SupportAssignmentServiceImpl.class);
    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final SupportAssignmentRepository supportRepository;
    private final UserRepository userRepository;

    public SupportAssignmentServiceImpl(DepartmentRepository departmentRepository,
                                        StaffRepository staffRepository,
                                        TaskRepository taskRepository,
                                        AssignmentRepository assignmentRepository,
                                        SupportAssignmentRepository supportRepository,
                                        UserRepository userRepository) {
        this.departmentRepository = departmentRepository;
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.supportRepository = supportRepository;
        this.userRepository = userRepository;
    }

    // ── current data ─────────────────────────────────────────────────────────────────────────────────────────

    private static final class Stats {
        final Department department;
        final List<Staff> activeStaff = new ArrayList<>();
        final List<Staff> available = new ArrayList<>();
        final List<Staff> free = new ArrayList<>();
        final List<Staff> lendable = new ArrayList<>();
        final List<Task> waiting = new ArrayList<>();
        int assignedMinutes = 0;

        Stats(Department department) { this.department = department; }
    }

    private static final class State {
        final LocalDate today = LocalDate.now();
        final Map<String, Stats> byCode = new LinkedHashMap<>();
        final Map<UUID, Staff> staffById = new HashMap<>();
        final Map<UUID, Task> activeTasksById = new HashMap<>();
        final Set<UUID> busyStaff = new HashSet<>();
        final Set<UUID> assignedTasks = new HashSet<>();
        final Map<UUID, Integer> minutesByStaff = new HashMap<>();
        final Map<UUID, Integer> countByStaff = new HashMap<>();
    }

    private State load() {
        State state = new State();
        for (Department department : departmentRepository.findAllByOrderByCreatedAtAscNameAsc()) {
            if (department.isActive()) state.byCode.put(department.getCode(), new Stats(department));
        }

        Map<UUID, SupportAssignment> supportByTask = new HashMap<>();
        for (SupportAssignment support : supportRepository.findByStatus(SupportStatus.ACTIVE)) supportByTask.put(support.getTaskId(), support);

        List<Assignment> assignments = assignmentRepository.findAllActiveAssignments(ACTIVE_STATUSES);
        for (Assignment assignment : assignments) {
            UUID staffId = assignment.getStaff().getId();
            state.busyStaff.add(staffId);
            state.assignedTasks.add(assignment.getTask().getId());
            int minutes = minutes(assignment.getTask());
            state.minutesByStaff.merge(staffId, minutes, Integer::sum);
            state.countByStaff.merge(staffId, 1, Integer::sum);
            // A supported task counts under the receiving department, so it is never counted twice
            SupportAssignment support = supportByTask.get(assignment.getTask().getId());
            String code = support != null ? support.getReceivingDepartmentCode()
                    : assignment.getStaff().getDepartment() == null ? null : assignment.getStaff().getDepartment().getCode();
            Stats stats = code == null ? null : state.byCode.get(code);
            if (stats != null) stats.assignedMinutes += minutes;
        }

        for (Staff member : staffRepository.findAllWithUser()) {
            state.staffById.put(member.getId(), member);
            Stats stats = member.getDepartment() == null ? null : state.byCode.get(member.getDepartment().getCode());
            if (stats == null || !member.isActive()) continue;
            stats.activeStaff.add(member);
            if (member.getAvailability() == Availability.AVAILABLE) {
                stats.available.add(member);
                if (!state.busyStaff.contains(member.getId())) {
                    stats.free.add(member);
                    if (WorkloadRecommendationEngine.canBeSuggested(member)) stats.lendable.add(member);
                }
            }
        }

        for (Task task : taskRepository.findByStatusIn(ACTIVE_STATUSES)) {
            state.activeTasksById.put(task.getId(), task);
            Stats stats = task.getDepartment() == null ? null : state.byCode.get(task.getDepartment().getCode());
            if (stats != null && !state.assignedTasks.contains(task.getId())) stats.waiting.add(task);
        }

        Comparator<Staff> byName = Comparator.comparing((Staff s) -> String.valueOf(s.getName()).toLowerCase()).thenComparing(s -> String.valueOf(s.getId()));
        for (Stats stats : state.byCode.values()) {
            stats.free.sort(byName);
            stats.lendable.sort(byName);
            stats.waiting.sort(WorkloadRecommendationEngine.MOST_IMPORTANT_FIRST);
        }
        return state;
    }

    private static int minutes(Task task) {
        return Objects.requireNonNullElse(task.getEstimatedDuration(), 0);
    }

    private static int minutes(List<Task> tasks) {
        int total = 0;
        for (Task task : tasks) total += minutes(task);
        return total;
    }

    private static Metrics metrics(Stats stats, List<Task> waiting, int freeStaff, int assignedMinutes) {
        int uncovered = Math.max(0, waiting.size() - freeStaff);
        List<Task> uncoveredTasks = waiting.subList(Math.min(freeStaff, waiting.size()), waiting.size());
        return new Metrics(waiting.size(), minutes(waiting), assignedMinutes, stats.available.size(), freeStaff, uncovered, minutes(uncoveredTasks));
    }

    private static Metrics metrics(Stats stats) {
        return metrics(stats, stats.waiting, stats.free.size(), stats.assignedMinutes);
    }

    // ── options ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Options getOptions(String receivingDepartmentCode) {
        State state = load();
        Stats receiving = state.byCode.get(receivingDepartmentCode == null ? "" : receivingDepartmentCode.trim());
        if (receiving == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found or not active");
        }

        int urgentOrHigh = 0;
        int dueSoon = 0;
        for (Task task : receiving.waiting) {
            if (task.getPriority() == Priority.URGENT || task.getPriority() == Priority.HIGH) urgentOrHigh++;
            if (task.getDueDate() != null && !task.getDueDate().isAfter(state.today.plusDays(WorkloadRecommendationEngine.DUE_SOON_DAYS))) dueSoon++;
        }
        Metrics receivingMetrics = metrics(receiving);
        DepartmentSummary summary = new DepartmentSummary(receiving.department.getCode(), receiving.department.getName(),
                severity(receiving, state.today), urgentOrHigh, dueSoon, receiving.activeStaff.size(), receivingMetrics);

        List<WaitingTask> tasks = receiving.waiting.stream()
                .map(t -> new WaitingTask(t.getId(), t.getTitle(), t.getCategory(), t.getPriority(), t.getEstimatedDuration(), t.getDueDate(), t.getDeadlineTime()))
                .toList();

        List<DonorDepartment> donors = new ArrayList<>();
        for (Stats donor : state.byCode.values()) {
            if (donor == receiving || donor.lendable.isEmpty()) continue;
            int required = Math.max(1, donor.waiting.size());
            int maxLendable = Math.min(donor.lendable.size(), Math.max(0, donor.free.size() - required));
            // With the manager's acknowledgement the one-person reserve may go, but the donor's own waiting work stays covered
            int overrideLendable = Math.max(maxLendable, Math.min(donor.lendable.size(), Math.max(0, donor.free.size() - donor.waiting.size())));
            String note = maxLendable > 0 ? null
                    : donor.free.size() <= 1 ? "Its only free staff member stays in reserve."
                    : "Its free staff are needed for its own waiting work and reserve.";
            List<DonorStaff> staff = donor.lendable.stream().map(s -> {
                int mins = state.minutesByStaff.getOrDefault(s.getId(), 0);
                return new DonorStaff(s.getId(), s.getName(), s.getAvailability(), state.countByStaff.getOrDefault(s.getId(), 0), mins, WorkloadDto.deriveStatus(mins));
            }).toList();
            donors.add(new DonorDepartment(donor.department.getCode(), donor.department.getName(), donor.free.size(), donor.available.size(),
                    donor.waiting.size(), maxLendable, overrideLendable, maxLendable > 0, note, staff));
        }

        return new Options(summary, tasks, receivingMetrics.uncoveredTasks(), donors, limitations());
    }

    private static Severity severity(Stats stats, LocalDate today) {
        boolean critical = false;
        boolean high = false;
        List<Task> uncovered = stats.waiting.subList(Math.min(stats.free.size(), stats.waiting.size()), stats.waiting.size());
        for (Task task : uncovered) {
            if (task.getPriority() == Priority.URGENT || (task.getDueDate() != null && task.getDueDate().isBefore(today))) critical = true;
            if (task.getPriority() == Priority.HIGH || (task.getDueDate() != null && !task.getDueDate().isAfter(today.plusDays(WorkloadRecommendationEngine.DUE_SOON_DAYS)))) high = true;
        }
        return critical ? Severity.CRITICAL : high ? Severity.HIGH : Severity.MODERATE;
    }

    private static List<String> limitations() {
        return List.of(
                "Skills are not recorded in Taskotel, so a person's suitability for the task is not verified. You decide whether they can do it.",
                "Shifts and working hours are not recorded. \"Available\" means active, marked Available and with no active task right now.",
                "Only active STAFF accounts that can sign in (email verified, credentials set up) can be selected.",
                "Support lasts until the task is completed. The person stays in their home department the whole time.");
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────────────────────

    private record Pair(Staff staff, Task task) {}

    private static final class Resolved {
        final List<Problem> problems = new ArrayList<>();
        final List<Pair> pairs = new ArrayList<>();
        final Map<String, Integer> lendByDonor = new LinkedHashMap<>();
        /** Donors left below their reserve with the manager's acknowledgement: allowed, but never silent. */
        final List<Problem> warnings = new ArrayList<>();
        final Set<String> overriddenDonors = new LinkedHashSet<>();
        /** A donor whose own waiting work would be left uncovered: not even an acknowledgement can allow that. */
        boolean reserveHardBlocked;
        boolean acknowledged;
        Stats receiving;
    }

    private Resolved resolve(SupportPlanRequest request, State state) {
        Resolved resolved = new Resolved();
        String code = request.getReceivingDepartment() == null ? "" : request.getReceivingDepartment().trim();
        Stats receiving = state.byCode.get(code);
        if (receiving == null) {
            resolved.problems.add(new Problem("DEPARTMENT_NOT_FOUND", "The receiving department does not exist or is not active.", null, null));
            return resolved;
        }
        resolved.receiving = receiving;
        resolved.acknowledged = request.isAcknowledgeReserveOverride();

        Set<UUID> seenStaff = new HashSet<>();
        Set<UUID> seenTasks = new HashSet<>();
        for (SupportPlanRequest.Item item : request.getItems()) {
            Staff staff = state.staffById.get(item.getStaffId());
            Task task = state.activeTasksById.get(item.getTaskId());
            if (task == null) task = taskRepository.findById(item.getTaskId()).orElse(null);
            boolean ok = true;

            if (staff == null) {
                resolved.problems.add(new Problem("STAFF_NOT_FOUND", "A selected staff member no longer exists.", item.getStaffId(), item.getTaskId()));
                ok = false;
            }
            if (task == null) {
                resolved.problems.add(new Problem("TASK_NOT_FOUND", "A selected task no longer exists.", item.getStaffId(), item.getTaskId()));
                ok = false;
            }
            if (!seenStaff.add(item.getStaffId())) {
                resolved.problems.add(new Problem("DUPLICATE_STAFF", (staff == null ? "A staff member" : staff.getName()) + " is selected more than once.", item.getStaffId(), item.getTaskId()));
                ok = false;
            }
            if (!seenTasks.add(item.getTaskId())) {
                resolved.problems.add(new Problem("DUPLICATE_TASK", (task == null ? "A task" : "\"" + task.getTitle() + "\"") + " is selected more than once.", item.getStaffId(), item.getTaskId()));
                ok = false;
            }
            if (staff != null && !checkStaff(staff, item, receiving, state, resolved)) ok = false;
            if (task != null && !checkTask(task, item, receiving, state, resolved)) ok = false;
            if (ok) resolved.pairs.add(new Pair(staff, task));
        }

        if (resolved.pairs.size() > receiving.waiting.size() - Math.min(receiving.free.size(), receiving.waiting.size())) {
            int uncovered = Math.max(0, receiving.waiting.size() - receiving.free.size());
            resolved.problems.add(new Problem("NOT_NEEDED", receiving.department.getName() + " has " + uncovered + " task"
                    + (uncovered == 1 ? "" : "s") + " its own free staff cannot take, so it can use at most " + uncovered + " helper"
                    + (uncovered == 1 ? "" : "s") + ".", null, null));
        }

        for (Pair pair : resolved.pairs) resolved.lendByDonor.merge(pair.staff().getDepartment().getCode(), 1, Integer::sum);
        for (Map.Entry<String, Integer> lend : resolved.lendByDonor.entrySet()) {
            Stats donor = state.byCode.get(lend.getKey());
            if (donor == null) continue;
            int ownWork = donor.waiting.size();
            int required = Math.max(1, ownWork);
            int freeAfter = donor.free.size() - lend.getValue();
            if (freeAfter < required) {
                // Only the one-person reserve can be acknowledged away: the donor's own waiting work must stay covered
                boolean acknowledgeable = freeAfter >= ownWork;
                if (acknowledgeable && resolved.acknowledged) {
                    resolved.overriddenDonors.add(lend.getKey());
                    resolved.warnings.add(new Problem("RESERVE_OVERRIDE", donor.department.getName() + " will be left with " + freeAfter
                            + " free staff, below the " + required + " it normally keeps in reserve. Acknowledged by the manager.", null, null));
                } else {
                    if (!acknowledgeable) resolved.reserveHardBlocked = true;
                    resolved.problems.add(new Problem("DONOR_RESERVE", donor.department.getName() + " would be left with " + Math.max(0, freeAfter)
                            + " free staff; it must keep at least " + required + " (a free person in reserve and enough for its own "
                            + ownWork + " waiting task" + (ownWork == 1 ? "" : "s") + ").", null, null));
                }
            }
        }
        return resolved;
    }

    private boolean checkStaff(Staff staff, SupportPlanRequest.Item item, Stats receiving, State state, Resolved resolved) {
        boolean ok = true;
        String name = staff.getName();
        String home = staff.getDepartment() == null ? null : staff.getDepartment().getCode();
        if (home != null && home.equals(receiving.department.getCode())) {
            resolved.problems.add(new Problem("SAME_DEPARTMENT", name + " already belongs to " + receiving.department.getName() + ". Assign them to its tasks normally.", staff.getId(), item.getTaskId()));
            return false;
        }
        if (home == null || !state.byCode.containsKey(home)) {
            resolved.problems.add(new Problem("DONOR_INACTIVE", name + "'s department is not active.", staff.getId(), item.getTaskId()));
            ok = false;
        }
        if (!staff.isActive()) {
            resolved.problems.add(new Problem("STAFF_INACTIVE", name + " is not active.", staff.getId(), item.getTaskId()));
            ok = false;
        } else if (staff.getAvailability() != Availability.AVAILABLE) {
            resolved.problems.add(new Problem("STAFF_UNAVAILABLE", name + " is marked Unavailable.", staff.getId(), item.getTaskId()));
            ok = false;
        }
        User account = staff.getUser();
        if (account == null || account.getRole() != com.hotel.workload.security.Role.STAFF) {
            resolved.problems.add(new Problem("NOT_STAFF_ROLE", name + " is not a STAFF account, so they cannot be lent.", staff.getId(), item.getTaskId()));
            ok = false;
        } else if (!WorkloadRecommendationEngine.canBeSuggested(staff)) {
            resolved.problems.add(new Problem("CANNOT_SIGN_IN", name + " cannot sign in yet (account disabled, email not verified or credentials setup pending).", staff.getId(), item.getTaskId()));
            ok = false;
        }
        if (state.busyStaff.contains(staff.getId())) {
            resolved.problems.add(new Problem("STAFF_BUSY", name + " already has an active task.", staff.getId(), item.getTaskId()));
            ok = false;
        }
        return ok;
    }

    private boolean checkTask(Task task, SupportPlanRequest.Item item, Stats receiving, State state, Resolved resolved) {
        String title = "\"" + task.getTitle() + "\"";
        if (task.getDepartment() == null || !task.getDepartment().getCode().equals(receiving.department.getCode())) {
            resolved.problems.add(new Problem("TASK_WRONG_DEPARTMENT", title + " does not belong to " + receiving.department.getName() + ".", item.getStaffId(), task.getId()));
            return false;
        }
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            resolved.problems.add(new Problem("TASK_FINISHED", title + " is already " + task.getStatus().getDisplayName().toLowerCase() + ".", item.getStaffId(), task.getId()));
            return false;
        }
        if (state.assignedTasks.contains(task.getId()) || task.getStatus() != TaskStatus.PENDING) {
            resolved.problems.add(new Problem("TASK_ALREADY_ASSIGNED", title + " is already assigned.", item.getStaffId(), task.getId()));
            return false;
        }
        return true;
    }

    // ── preview ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Preview preview(SupportPlanRequest request) {
        State state = load();
        Resolved resolved = resolve(request, state);
        return buildPreview(state, resolved);
    }

    private Preview buildPreview(State state, Resolved resolved) {
        List<PreviewItem> items = resolved.pairs.stream().map(p -> new PreviewItem(p.staff().getId(), p.staff().getName(),
                p.staff().getDepartment().getCode(), p.staff().getDepartment().getName(), p.task().getId(), p.task().getTitle(),
                p.task().getPriority(), minutes(p.task()))).toList();
        if (resolved.receiving == null) {
            return new Preview(false, resolved.problems, resolved.warnings, false, items, null, List.of(), fingerprint(state, resolved), limitations());
        }

        Stats receiving = resolved.receiving;
        Set<UUID> chosenTasks = resolved.pairs.stream().map(p -> p.task().getId()).collect(Collectors.toSet());
        List<Task> remaining = receiving.waiting.stream().filter(t -> !chosenTasks.contains(t.getId())).toList();
        int addedMinutes = resolved.pairs.stream().mapToInt(p -> minutes(p.task())).sum();
        DepartmentImpact receivingImpact = new DepartmentImpact("RECEIVING", receiving.department.getCode(), receiving.department.getName(),
                metrics(receiving), metrics(receiving, remaining, receiving.free.size(), receiving.assignedMinutes + addedMinutes), 0, resolved.pairs.size());

        List<DepartmentImpact> donors = new ArrayList<>();
        for (Map.Entry<String, Integer> lend : resolved.lendByDonor.entrySet()) {
            Stats donor = state.byCode.get(lend.getKey());
            if (donor == null) continue;
            // The lent person's task minutes are counted under the receiving department, so the donor's minutes do not change
            donors.add(new DepartmentImpact("DONOR", donor.department.getCode(), donor.department.getName(), metrics(donor),
                    metrics(donor, donor.waiting, Math.max(0, donor.free.size() - lend.getValue()), donor.assignedMinutes),
                    Math.max(1, donor.waiting.size()), lend.getValue()));
        }
        // Blocked only by the donor reserve, and acknowledging the risk would allow it
        boolean canOverride = !resolved.problems.isEmpty() && !resolved.reserveHardBlocked
                && resolved.problems.stream().allMatch(p -> "DONOR_RESERVE".equals(p.code()));
        return new Preview(resolved.problems.isEmpty(), resolved.problems, resolved.warnings, canOverride, items, receivingImpact, donors,
                fingerprint(state, resolved), limitations());
    }

    /** A hash of everything the plan's validity depends on, so a confirm can tell the preview is out of date. */
    private String fingerprint(State state, Resolved resolved) {
        StringBuilder sb = new StringBuilder();
        sb.append("ack=").append(resolved.acknowledged).append(';');
        if (resolved.receiving != null) {
            sb.append(resolved.receiving.department.getCode()).append('|').append(resolved.receiving.free.size()).append('|').append(resolved.receiving.waiting.size()).append(';');
        }
        List<Pair> ordered = new ArrayList<>(resolved.pairs);
        ordered.sort(Comparator.comparing(p -> p.staff().getId().toString()));
        for (Pair pair : ordered) {
            Staff s = pair.staff();
            Task t = pair.task();
            User u = s.getUser();
            sb.append(s.getId()).append('|').append(s.isActive()).append('|').append(s.getAvailability()).append('|').append(state.busyStaff.contains(s.getId()))
                    .append('|').append(u == null ? "-" : u.getRole() + "," + u.isActive() + "," + u.isEmailVerified() + "," + u.isCredentialsSetupRequired())
                    .append('|').append(t.getId()).append('|').append(t.getStatus()).append('|').append(state.assignedTasks.contains(t.getId())).append('|').append(t.getUpdatedAt()).append(';');
        }
        for (Map.Entry<String, Integer> lend : resolved.lendByDonor.entrySet()) {
            Stats donor = state.byCode.get(lend.getKey());
            if (donor != null) sb.append(lend.getKey()).append('|').append(donor.free.size()).append('|').append(donor.waiting.size()).append(';');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── confirm ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public List<SupportAssignmentDto> confirm(SupportPlanRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to confirm support");
        }
        String username = auth.getName();

        // Serialize with other confirmations that touch the same departments, tasks or staff, in a fixed order
        Set<UUID> staffIds = request.getItems().stream().map(SupportPlanRequest.Item::getStaffId).collect(Collectors.toCollection(TreeSet::new));
        Set<UUID> taskIds = request.getItems().stream().map(SupportPlanRequest.Item::getTaskId).collect(Collectors.toCollection(TreeSet::new));
        Set<String> codes = new TreeSet<>(staffRepository.departmentCodesOf(staffIds));
        if (request.getReceivingDepartment() != null) codes.add(request.getReceivingDepartment().trim());
        departmentRepository.lockByCodes(codes);
        taskRepository.lockByIds(taskIds);
        staffRepository.lockByIds(staffIds);

        // Everything is checked again on the data as it is now
        State state = load();
        Resolved resolved = resolve(request, state);
        if (request.getFingerprint() != null && resolved.receiving != null
                && !request.getFingerprint().equals(fingerprint(state, resolved))) {
            throw new SupportPlanException(HttpStatus.CONFLICT,
                    "This plan is out of date: staff or tasks changed since the preview. Review it again before confirming.",
                    List.of(new Problem("STALE_PLAN", "Staff, tasks or department capacity changed since the preview.", null, null)));
        }
        if (!resolved.problems.isEmpty()) {
            Problem first = resolved.problems.get(0);
            throw new SupportPlanException(HttpStatus.CONFLICT, "The plan cannot be confirmed: " + first.message()
                    + (resolved.problems.size() > 1 ? " (" + (resolved.problems.size() - 1) + " more problem" + (resolved.problems.size() == 2 ? "" : "s") + ")" : ""),
                    resolved.problems);
        }

        UUID planId = UUID.randomUUID();
        UUID approverId = userRepository.findByUsername(username).map(User::getId).orElse(null);
        List<SupportAssignment> created = new ArrayList<>();
        try {
            for (Pair pair : resolved.pairs) {
                Assignment assignment = new Assignment();
                assignment.setTask(pair.task());
                assignment.setStaff(pair.staff());
                assignment = assignmentRepository.save(assignment);

                pair.task().setStatus(TaskStatus.ASSIGNED);
                taskRepository.save(pair.task());

                SupportAssignment support = new SupportAssignment();
                support.setPlanId(planId);
                support.setStaffId(pair.staff().getId());
                support.setStaffName(pair.staff().getName());
                support.setHomeDepartmentCode(pair.staff().getDepartment().getCode());
                support.setReceivingDepartmentCode(resolved.receiving.department.getCode());
                support.setTaskId(pair.task().getId());
                support.setTaskTitle(pair.task().getTitle());
                support.setAssignmentId(assignment.getId());
                support.setApprovedByUserId(approverId);
                support.setApprovedByUsername(username);
                created.add(supportRepository.save(support));
            }
            assignmentRepository.flush();
            supportRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // A competing request got there first; the database refused, so nothing from this plan is kept
            throw new SupportPlanException(HttpStatus.CONFLICT, "A task in this plan was assigned by someone else. Review it again.",
                    List.of(new Problem("TASK_ALREADY_ASSIGNED", "A task in this plan was assigned by someone else.", null, null)));
        }
        if (!resolved.overriddenDonors.isEmpty()) {
            // The override is reported in the response and logged; storing it would need a schema change (proposed separately)
            log.warn("Support reserve override: {} acknowledged leaving {} below its reserve (plan {}, receiving {})",
                    username, resolved.overriddenDonors, planId, resolved.receiving.department.getCode());
        }
        return created.stream()
                .map(s -> SupportAssignmentDto.from(s, null, resolved.overriddenDonors.contains(s.getHomeDepartmentCode())))
                .toList();
    }

    // ── history ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public List<SupportAssignmentDto> list(SupportStatus status) {
        reconcile();
        // Archived records stay in the database as an audit trail but are no longer listed
        List<SupportAssignment> rows = status == null
                ? supportRepository.findAllByArchivedAtIsNullOrderByCreatedAtDesc()
                : supportRepository.findByStatusAndArchivedAtIsNullOrderByCreatedAtDesc(status);
        Map<UUID, TaskStatus> taskStatuses = new HashMap<>();
        taskRepository.findAllById(rows.stream().filter(r -> r.getStatus() == SupportStatus.ACTIVE).map(SupportAssignment::getTaskId).toList())
                .forEach(task -> taskStatuses.put(task.getId(), task.getStatus()));
        return rows.stream().map(row -> SupportAssignmentDto.from(row, taskStatuses.get(row.getTaskId()))).toList();
    }

    // ── removing an ended record from the history ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SupportAssignmentDto archive(UUID supportId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to remove support history");
        }
        // Only this record is touched: no task, assignment, staff or department is read for change
        SupportAssignment support = supportRepository.lockById(supportId).orElseThrow(() -> supportNotFound());
        if (support.getStatus() == SupportStatus.ACTIVE) {
            throw new SupportPlanException(HttpStatus.CONFLICT, "This support is still active. End it first, then remove it from the history.",
                    List.of(new Problem("SUPPORT_ACTIVE", "Active support cannot be removed from the history.", support.getStaffId(), support.getTaskId())));
        }
        if (support.getArchivedAt() != null) {
            throw new SupportPlanException(HttpStatus.CONFLICT, "This record has already been removed from the history. Refresh to see the latest.",
                    List.of(new Problem("ALREADY_ARCHIVED", "This record was already removed from the history.", support.getStaffId(), support.getTaskId())));
        }
        support.archive(auth.getName(), LocalDateTime.now());
        return SupportAssignmentDto.from(supportRepository.saveAndFlush(support), null);
    }

    // ── ending support by hand ───────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SupportAssignmentDto end(UUID supportId) {
        // Lock in the same order as the task flows (task, staff, then the support record) and read the record only
        // after the locks, so a request that waited sees what the winner did instead of a stale copy.
        UUID taskId = supportRepository.findTaskIdById(supportId).orElseThrow(() -> supportNotFound());
        taskRepository.lockByIds(Set.of(taskId));
        SupportAssignment support = supportRepository.lockById(supportId).orElseThrow(() -> supportNotFound());
        staffRepository.lockByIds(Set.of(support.getStaffId()));

        if (support.getStatus() != SupportStatus.ACTIVE) {
            throw new SupportPlanException(HttpStatus.CONFLICT, "This support has already ended. Refresh to see the latest.",
                    List.of(new Problem("ALREADY_ENDED", "This support has already ended.", support.getStaffId(), support.getTaskId())));
        }

        LocalDateTime now = LocalDateTime.now();
        Task task = taskRepository.findById(support.getTaskId()).orElse(null);
        if (task == null) {
            support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_DELETED, now);
            return SupportAssignmentDto.from(supportRepository.saveAndFlush(support), null);
        }
        // The task finished or moved meanwhile: record what really happened rather than a manual end
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED
                || task.getDepartment() == null || !task.getDepartment().getCode().equals(support.getReceivingDepartmentCode())) {
            endIfTaskDone(support, task);
            return SupportAssignmentDto.from(support, task.getStatus());
        }
        if (task.getStatus() == TaskStatus.IN_PROGRESS) {
            throw new SupportPlanException(HttpStatus.CONFLICT,
                    "\"" + task.getTitle() + "\" is already in progress. Support cannot be ended until the task is completed or reassigned.",
                    List.of(new Problem("TASK_IN_PROGRESS", "The task is already in progress.", support.getStaffId(), task.getId())));
        }

        List<Assignment> held = assignmentRepository.findAllByTaskId(task.getId());
        List<Assignment> mine = held.stream().filter(a -> a.getStaff().getId().equals(support.getStaffId())).toList();
        if (held.size() > mine.size()) {
            // Someone else holds the task now: nothing to release, the support just no longer applies
            support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_REASSIGNED, now);
        } else {
            // Release the temporary assignment and put the task back in its department's waiting list
            assignmentRepository.deleteAll(mine);
            task.setStatus(TaskStatus.PENDING);
            taskRepository.save(task);
            support.end(SupportStatus.CANCELLED, SupportEndReason.MANUALLY_ENDED, now);
        }
        supportRepository.saveAndFlush(support);
        return SupportAssignmentDto.from(support, task.getStatus());
    }

    /** A coded 404, so a client can tell "this record does not exist" from "this server has no such route". */
    private static SupportPlanException supportNotFound() {
        return new SupportPlanException(HttpStatus.NOT_FOUND, "This support record no longer exists. Refresh to see the latest.",
                List.of(new Problem("SUPPORT_NOT_FOUND", "This support record does not exist.", null, null)));
    }

    /** Safety net: closes active support whose task is gone or finished, whatever path changed it. */
    private void reconcile() {
        for (SupportAssignment support : supportRepository.findByStatus(SupportStatus.ACTIVE)) {
            Optional<Task> task = taskRepository.findById(support.getTaskId());
            if (task.isEmpty()) {
                support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_DELETED, LocalDateTime.now());
                supportRepository.save(support);
            } else {
                endIfTaskDone(support, task.get());
            }
        }
    }

    // ── lifecycle: support ends when its task does ────────────────────────────────────────────────────────────

    private void endIfTaskDone(SupportAssignment support, Task task) {
        LocalDateTime now = LocalDateTime.now();
        if (task.getStatus() == TaskStatus.COMPLETED) {
            support.end(SupportStatus.COMPLETED, SupportEndReason.TASK_COMPLETED, now);
        } else if (task.getStatus() == TaskStatus.CANCELLED) {
            support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_CANCELLED, now);
        } else if (task.getDepartment() == null || !task.getDepartment().getCode().equals(support.getReceivingDepartmentCode())) {
            support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_MOVED, now);
        } else {
            return;
        }
        supportRepository.save(support);
    }

    @Override
    @Transactional
    public void onTaskChanged(Task task) {
        supportRepository.findByTaskIdAndStatus(task.getId(), SupportStatus.ACTIVE).ifPresent(support -> endIfTaskDone(support, task));
    }

    @Override
    @Transactional
    public void onTaskDeleted(UUID taskId) {
        supportRepository.findByTaskIdAndStatus(taskId, SupportStatus.ACTIVE).ifPresent(support -> {
            support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_DELETED, LocalDateTime.now());
            supportRepository.save(support);
        });
    }

    @Override
    @Transactional
    public void onTaskReassigned(UUID taskId, UUID newStaffId) {
        supportRepository.findByTaskIdAndStatus(taskId, SupportStatus.ACTIVE).ifPresent(support -> {
            if (!support.getStaffId().equals(newStaffId)) {
                support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_REASSIGNED, LocalDateTime.now());
                supportRepository.save(support);
            }
        });
    }
}
