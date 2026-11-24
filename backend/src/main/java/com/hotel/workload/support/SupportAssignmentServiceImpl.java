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
import com.hotel.workload.workload.DepartmentCapacity;
import com.hotel.workload.workload.DepartmentCapacityService;
import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.WorkloadStatus;
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
    private final com.hotel.workload.security.CurrentUserScopeResolver scopeResolver;
    /** Tracks workload changes for alert emails. Optional: a service built by hand in a unit test tracks nothing. */
    private com.hotel.workload.notification.WorkloadAlertService workloadAlerts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkloadAlertService(com.hotel.workload.notification.WorkloadAlertService workloadAlerts) {
        this.workloadAlerts = workloadAlerts;
    }

    /** Tells the helpers, the department Manager(s) and the confirming Admin by email (after commit). Optional, like the workload alerts. */
    private com.hotel.workload.notification.TaskEmailNotifier taskEmails;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTaskEmailNotifier(com.hotel.workload.notification.TaskEmailNotifier taskEmails) {
        this.taskEmails = taskEmails;
    }

    public SupportAssignmentServiceImpl(DepartmentRepository departmentRepository,
                                        StaffRepository staffRepository,
                                        TaskRepository taskRepository,
                                        AssignmentRepository assignmentRepository,
                                        SupportAssignmentRepository supportRepository,
                                        UserRepository userRepository,
                                        com.hotel.workload.security.CurrentUserScopeResolver scopeResolver) {
        this.departmentRepository = departmentRepository;
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.supportRepository = supportRepository;
        this.userRepository = userRepository;
        this.scopeResolver = scopeResolver;
    }

    /** System Settings, Cross-Department Assistance. Optional (setter) so a hand-built service in a unit test keeps the default (allowed). */
    private com.hotel.workload.settings.SystemSettingsService systemSettings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSystemSettings(com.hotel.workload.settings.SystemSettingsService systemSettings) {
        this.systemSettings = systemSettings;
    }

    /** The shared department capacity figures. Optional (setter) so a hand-built service in a unit test keeps the original rules only. */
    private DepartmentCapacityService capacityService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDepartmentCapacityService(DepartmentCapacityService capacityService) {
        this.capacityService = capacityService;
    }

    static final String CROSS_DEPARTMENT_OFF_MESSAGE =
            "Cross-department assistance is turned off in System Settings, so new support arrangements cannot be planned or confirmed. "
                    + "Existing support is not affected.";

    /** Refuses a NEW cross-department arrangement while the setting is off. Existing support records are never read or changed by this. */
    private void requireCrossDepartmentAssistanceEnabled() {
        if (systemSettings != null && !systemSettings.current().crossDepartmentAssistanceEnabled()) {
            throw new SupportPlanException(HttpStatus.CONFLICT, CROSS_DEPARTMENT_OFF_MESSAGE,
                    List.of(new Problem("CROSS_DEPARTMENT_ASSISTANCE_DISABLED", CROSS_DEPARTMENT_OFF_MESSAGE, null, null)));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SupportDtos.CrossDepartmentStatus crossDepartmentStatus() {
        readerManagedCodes(); // who is asking: Admin or a valid Manager; anyone else is refused (401/403) before anything is read
        return new SupportDtos.CrossDepartmentStatus(systemSettings == null || systemSettings.current().crossDepartmentAssistanceEnabled());
    }

    // ── who is asking (read paths, Step 2G-2) ────────────────────────────────────────────────────────────────

    /**
     * Resolves the caller's scope ONCE for a request, from the database (never from a request value). Admin: all; a valid Manager: the
     * departments they manage; Staff are refused (403, also at the URL level); an invalid Manager (403) and no sign-in (401) are refused by
     * the resolver. Returns the codes of the departments the Manager manages (DepartmentScope.managedDepartmentCodes()), or null for an Admin.
     */
    private List<String> readerManagedCodes() {
        com.hotel.workload.security.DepartmentScope scope = scopeResolver.resolve();
        if (scope.isAll()) return null;
        if (scope.isDepartment()) return scope.managedDepartmentCodes();
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    private static final String DEPARTMENT_NOT_FOUND_MESSAGE = "Department not found or not active";

    // ── current data ─────────────────────────────────────────────────────────────────────────────────────────

    private static final class Stats {
        final Department department;
        final List<Staff> activeStaff = new ArrayList<>();
        final List<Staff> available = new ArrayList<>();
        final List<Staff> free = new ArrayList<>();
        final List<Staff> lendable = new ArrayList<>();
        final List<Task> waiting = new ArrayList<>();
        /** Tasks its own staff hold and no helper has yet: a helper may JOIN one while the department is at its limit. */
        final List<Task> shareable = new ArrayList<>();
        /** Only for a donor in a Manager's narrowed load: how many tasks wait there (its task details are never loaded). */
        Integer waitingCountOverride;
        int assignedMinutes = 0;
        /** Remaining task minutes (assigned + waiting) and the people whose capacity counts here, from DepartmentCapacityService. */
        int remainingMinutes;
        int capacityStaff;

        Stats(Department department) { this.department = department; }

        int waitingCount() { return waitingCountOverride != null ? waitingCountOverride : waiting.size(); }
    }

    private static final class State {
        /** True for a Manager's narrowed load: donor departments carry counts, not tasks, and no donor workload figures exist. */
        boolean narrowed;
        /**
         * In a Manager's narrowed load, the donor departments the Manager also manages: they are loaded in full (waiting tasks and assigned
         * minutes, like the receiving department), so their figures can be shown. Every other donor stays counts-only.
         */
        final Set<String> managedDonors = new HashSet<>();
        /** A Manager's managed department codes (receiver and every donor must be in it); null means unrestricted (Admin). */
        Set<String> allowedCodes;
        final LocalDate today = LocalDate.now();
        final Map<String, Stats> byCode = new LinkedHashMap<>();
        final Map<UUID, Staff> staffById = new HashMap<>();
        final Map<UUID, Task> activeTasksById = new HashMap<>();
        final Set<UUID> busyStaff = new HashSet<>();
        final Set<UUID> assignedTasks = new HashSet<>();
        final Map<UUID, Integer> minutesByStaff = new HashMap<>();
        final Map<UUID, Integer> countByStaff = new HashMap<>();
        /** True when the capacity figures are available (then the capacity rules apply); risk is the saved switch and threshold. */
        boolean modelled;
        DepartmentCapacity.Risk risk = DepartmentCapacity.Risk.OFF;
    }

    /**
     * Fills every department's remaining minutes and capacity staff from the shared capacity figures (aggregates only, so a Manager's narrowed
     * load still knows each donor's utilization without loading its tasks). Without the capacity service the original rules apply alone.
     */
    private void applyCapacity(State state) {
        if (capacityService == null) return;
        state.modelled = true;
        state.risk = capacityService.risk();
        Map<String, DepartmentCapacityService.Figures> figures = capacityService.figures();
        for (Stats stats : state.byCode.values()) {
            DepartmentCapacityService.Figures f = figures.get(stats.department.getCode());
            if (f == null) continue;
            stats.remainingMinutes = f.remainingMinutes();
            stats.capacityStaff = f.eligibleStaff();
        }
    }

    /**
     * The state for a Manager's options and preview, read with queries limited to what the calculation needs: the receiving department's staff
     * and waiting tasks, the people who are active and available anywhere (the possible helpers, and each donor's free count for the reserve
     * rule), the NUMBER of waiting tasks per other department, the ids of busy staff and of held tasks, and one aggregate of assigned minutes.
     * No other department's task, assignment or support record is loaded, EXCEPT for the departments the Manager manages
     * ({@code managedCodes}): such a donor is loaded like the receiving department (its waiting tasks and assigned minutes), because the
     * Manager may see its figures. The figures it produces are the same ones {@link #load()} produces for the receiving department and for
     * the donors' reserve rule, so the algorithm and the stale-plan fingerprint are unchanged.
     */
    private State loadForReceiver(String receiverCode, java.util.Collection<UUID> itemStaffIds, java.util.Collection<String> managedCodes) {
        State state = new State();
        state.narrowed = true;
        Department receiver = null;
        for (Department department : departmentRepository.findAllByOrderByCreatedAtAscNameAsc()) {
            if (!department.isActive()) continue;
            state.byCode.put(department.getCode(), new Stats(department));
            if (department.getCode().equals(receiverCode)) receiver = department;
        }
        if (receiver == null) return state;
        Stats receiving = state.byCode.get(receiverCode);

        state.busyStaff.addAll(assignmentRepository.findBusyStaffIds(ACTIVE_STATUSES));
        state.assignedTasks.addAll(assignmentRepository.findHeldTaskIdsByDepartment(receiver, ACTIVE_STATUSES));
        receiving.assignedMinutes = (int) assignmentRepository.sumSupportAttributedMinutes(receiver, receiverCode, ACTIVE_STATUSES);

        Map<UUID, Staff> people = new LinkedHashMap<>();
        for (Staff member : staffRepository.findActiveWithUserByDepartment(receiver)) people.put(member.getId(), member);
        for (Staff member : staffRepository.findActiveWithUserByAvailability(Availability.AVAILABLE)) people.putIfAbsent(member.getId(), member);
        for (Staff member : people.values()) {
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
        // The people named in a plan are looked up by id so each can be validated (a person outside the loaded set is validated as before)
        if (!itemStaffIds.isEmpty()) {
            for (Staff member : staffRepository.findAllById(itemStaffIds)) state.staffById.putIfAbsent(member.getId(), member);
        }

        for (Task task : taskRepository.findWaitingByDepartment(receiver, ACTIVE_STATUSES)) {
            state.activeTasksById.put(task.getId(), task);
            receiving.waiting.add(task);
        }
        // The receiving department's tasks its own staff hold and that have no helper yet (a helper may join one while it is at its limit)
        Set<UUID> alreadySupported = new HashSet<>(supportRepository.findTaskIdsByStatusAndReceivingDepartmentCodes(SupportStatus.ACTIVE, List.of(receiverCode)));
        for (Task task : taskRepository.findHeldByDepartments(List.of(receiver), ACTIVE_STATUSES)) {
            if (!WorkloadRecommendationEngine.canShare(task) || alreadySupported.contains(task.getId())) continue;
            state.activeTasksById.put(task.getId(), task);
            receiving.shareable.add(task);
        }
        // The donors this Manager also manages: loaded in full, like the receiving department (their tasks are NOT added to the lookup of
        // plannable tasks: only the receiving department's tasks can be planned, as before)
        for (String code : managedCodes) {
            Stats donor = state.byCode.get(code);
            if (donor == null || donor == receiving) continue;
            state.managedDonors.add(code);
            donor.waiting.addAll(taskRepository.findWaitingByDepartment(donor.department, ACTIVE_STATUSES));
            donor.assignedMinutes = (int) assignmentRepository.sumSupportAttributedMinutes(donor.department, code, ACTIVE_STATUSES);
        }
        for (Object[] row : taskRepository.countWaitingByDepartmentCode(ACTIVE_STATUSES)) {
            Stats stats = state.byCode.get((String) row[0]);
            if (stats != null && stats != receiving && !state.managedDonors.contains((String) row[0])) stats.waitingCountOverride = ((Number) row[1]).intValue();
        }
        for (Stats stats : state.byCode.values()) {
            if (stats != receiving && !state.managedDonors.contains(stats.department.getCode()) && stats.waitingCountOverride == null) stats.waitingCountOverride = 0;
        }

        Comparator<Staff> byName = Comparator.comparing((Staff s) -> String.valueOf(s.getName()).toLowerCase()).thenComparing(s -> String.valueOf(s.getId()));
        for (Stats stats : state.byCode.values()) {
            stats.free.sort(byName);
            stats.lendable.sort(byName);
            stats.waiting.sort(WorkloadRecommendationEngine.MOST_IMPORTANT_FIRST);
            stats.shareable.sort(WorkloadRecommendationEngine.MOST_IMPORTANT_FIRST);
        }
        applyCapacity(state);
        return state;
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
            boolean firstHolder = state.assignedTasks.add(assignment.getTask().getId());
            int minutes = minutes(assignment.getTask());
            state.minutesByStaff.merge(staffId, minutes, Integer::sum);
            state.countByStaff.merge(staffId, 1, Integer::sum);
            // A supported task counts under the receiving department, so it is never counted twice
            SupportAssignment support = supportByTask.get(assignment.getTask().getId());
            String code = support != null ? support.getReceivingDepartmentCode()
                    : assignment.getStaff().getDepartment() == null ? null : assignment.getStaff().getDepartment().getCode();
            Stats stats = code == null ? null : state.byCode.get(code);
            // A task counts once for its department, however many people hold it
            if (stats != null && firstHolder) stats.assignedMinutes += minutes;
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

        Set<UUID> selfClaimed = new HashSet<>(assignmentRepository.findSelfClaimedTaskIds(ACTIVE_STATUSES)); // exclusively their owner's: never offered for a helper
        for (Task task : taskRepository.findByStatusIn(ACTIVE_STATUSES)) {
            state.activeTasksById.put(task.getId(), task);
            Stats stats = task.getDepartment() == null ? null : state.byCode.get(task.getDepartment().getCode());
            if (stats != null && !state.assignedTasks.contains(task.getId())) stats.waiting.add(task);
            else if (stats != null && WorkloadRecommendationEngine.canShare(task) && !supportByTask.containsKey(task.getId()) && !selfClaimed.contains(task.getId())) stats.shareable.add(task);
        }

        Comparator<Staff> byName = Comparator.comparing((Staff s) -> String.valueOf(s.getName()).toLowerCase()).thenComparing(s -> String.valueOf(s.getId()));
        for (Stats stats : state.byCode.values()) {
            stats.free.sort(byName);
            stats.lendable.sort(byName);
            stats.waiting.sort(WorkloadRecommendationEngine.MOST_IMPORTANT_FIRST);
            stats.shareable.sort(WorkloadRecommendationEngine.MOST_IMPORTANT_FIRST);
        }
        applyCapacity(state);
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

    /**
     * {@code capacityStaff} is the number of people whose capacity counts for this department in the situation described (now, or after a
     * plan). The remaining minutes are the department's own and never change with a plan: a helper takes a waiting task, which only moves
     * its minutes from waiting to assigned.
     */
    private static Metrics metrics(Stats stats, State state, List<Task> waiting, int freeStaff, int assignedMinutes, int capacityStaff) {
        int uncovered = Math.max(0, waiting.size() - freeStaff);
        List<Task> uncoveredTasks = waiting.subList(Math.min(freeStaff, waiting.size()), waiting.size());
        int remaining = state.modelled ? stats.remainingMinutes : assignedMinutes + minutes(waiting);
        int staff = state.modelled ? capacityStaff : 0;
        WorkloadStatus level = state.modelled ? DepartmentCapacity.level(remaining, staff, state.risk) : null;
        return new Metrics(waiting.size(), minutes(waiting), assignedMinutes, stats.available.size(), freeStaff, uncovered, minutes(uncoveredTasks),
                remaining, staff, DepartmentCapacity.capacityMinutes(staff), state.modelled ? DepartmentCapacity.utilizationPercent(remaining, staff) : null,
                level, level == WorkloadStatus.AT_RISK, state.modelled && DepartmentCapacity.noCapacity(remaining, staff));
    }

    private static Metrics metrics(Stats stats, State state) {
        return metrics(stats, state, stats.waiting, stats.free.size(), stats.assignedMinutes, stats.capacityStaff);
    }

    /** True when the department is at or above its Capacity Threshold (never while the alert is off, never without capacity figures). */
    private static boolean atRisk(Stats stats, State state) {
        return state.modelled && DepartmentCapacity.atRisk(stats.remainingMinutes, stats.capacityStaff, state.risk);
    }

    /**
     * How many helpers the department may use: the tasks its own free staff cannot take (the original rule), or, when it is at or above its
     * Capacity Threshold, as many as would bring it below it, never more than the tasks a helper could take or join (waiting, or held by its own
     * staff with no helper yet).
     */
    private static int allowedHelpers(Stats receiving, State state) {
        int uncovered = Math.max(0, receiving.waiting.size() - receiving.free.size());
        int forRisk = 0;
        if (atRisk(receiving, state)) {
            forRisk = Math.min(receiving.waiting.size() + receiving.shareable.size(),
                    DepartmentCapacity.helpersToLeaveRisk(receiving.remainingMinutes, receiving.capacityStaff, state.risk));
        }
        return Math.max(uncovered, forRisk);
    }

    private static String limitText(State state) {
        return state.risk.enabled() ? state.risk.thresholdPercent() + "%" : "a full shift per person";
    }

    private static String pct(Double value) {
        if (value == null) return "n/a";
        return value == Math.rint(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    // ── options ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Options getOptions(String receivingDepartmentCode) {
        List<String> managerCodes = readerManagedCodes();
        // Planning options (who could be lent, which tasks) belong to NEW support: unavailable to every role while the switch is off
        requireCrossDepartmentAssistanceEnabled();
        String requested = receivingDepartmentCode == null ? "" : receivingDepartmentCode.trim();
        if (managerCodes != null && !managerCodes.contains(requested)) {
            // A department the Manager does not manage is refused exactly like a department that does not exist
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, DEPARTMENT_NOT_FOUND_MESSAGE);
        }
        State state = managerCodes == null ? load() : loadForReceiver(requested, List.of(), managerCodes);
        Stats receiving = state.byCode.get(requested);
        if (receiving == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, DEPARTMENT_NOT_FOUND_MESSAGE);
        }

        int urgentOrHigh = 0;
        int dueSoon = 0;
        for (Task task : receiving.waiting) {
            if (task.getPriority() == Priority.URGENT || task.getPriority() == Priority.HIGH) urgentOrHigh++;
            if (task.getDueDate() != null && !task.getDueDate().isAfter(state.today.plusDays(WorkloadRecommendationEngine.DUE_SOON_DAYS))) dueSoon++;
        }
        Metrics receivingMetrics = metrics(receiving, state);
        boolean receivingAtRisk = atRisk(receiving, state);
        String needReason = receivingMetrics.uncoveredTasks() > 0 ? (receivingAtRisk ? "BOTH" : "UNCOVERED_TASKS") : receivingAtRisk ? "HIGH_UTILIZATION" : "NONE";
        DepartmentSummary summary = new DepartmentSummary(receiving.department.getCode(), receiving.department.getName(),
                severity(receiving, state.today), urgentOrHigh, dueSoon, receiving.activeStaff.size(), receivingMetrics, needReason);

        List<WaitingTask> tasks = new ArrayList<>(receiving.waiting.stream()
                .map(t -> new WaitingTask(t.getId(), t.getTitle(), t.getCategory(), t.getPriority(), t.getEstimatedDuration(), t.getDueDate(), t.getDeadlineTime(), false))
                .toList());
        // At or above its limit a department may also offer the tasks its own staff hold: a helper joins one (adds a person, moves no work)
        if (receivingAtRisk) {
            receiving.shareable.stream()
                    .map(t -> new WaitingTask(t.getId(), t.getTitle(), t.getCategory(), t.getPriority(), t.getEstimatedDuration(), t.getDueDate(), t.getDeadlineTime(), true))
                    .forEach(tasks::add);
        }

        List<DonorDepartment> donors = new ArrayList<>();
        for (Stats donor : state.byCode.values()) {
            if (donor == receiving || donor.lendable.isEmpty()) continue;
            int required = Math.max(1, donor.waitingCount());
            int maxLendable = Math.min(donor.lendable.size(), Math.max(0, donor.free.size() - required));
            // With the manager's acknowledgement the one-person reserve may go, but the donor's own waiting work stays covered
            int overrideLendable = Math.max(maxLendable, Math.min(donor.lendable.size(), Math.max(0, donor.free.size() - donor.waitingCount())));
            // Capacity rule: however many the reserve allows, the donor itself must stay below its limit with that many fewer people. Not overridable.
            int safeToLend = Integer.MAX_VALUE;
            if (state.modelled) {
                safeToLend = 0;
                while (safeToLend < donor.lendable.size() && !DepartmentCapacity.exceedsLimit(donor.remainingMinutes, donor.capacityStaff - (safeToLend + 1), state.risk)) safeToLend++;
            }
            boolean capacityLimited = state.modelled && maxLendable > safeToLend;
            maxLendable = Math.min(maxLendable, safeToLend);
            overrideLendable = Math.min(overrideLendable, safeToLend);
            String note = maxLendable > 0 ? null
                    : capacityLimited ? "Lending anyone would push it to or past its own capacity limit."
                    : donor.free.size() <= 1 ? "Its only free staff member stays in reserve."
                    : "Its free staff are needed for its own waiting work and reserve.";
            List<DonorStaff> staff = donor.lendable.stream().map(s -> {
                int mins = state.minutesByStaff.getOrDefault(s.getId(), 0);
                return new DonorStaff(s.getId(), s.getName(), s.getAvailability(), state.countByStaff.getOrDefault(s.getId(), 0), mins, WorkloadDto.deriveStatus(mins),
                        s.getUser() == null ? null : s.getUser().getProfileImageUrl());
            }).toList();
            donors.add(new DonorDepartment(donor.department.getCode(), donor.department.getName(), donor.free.size(), donor.available.size(),
                    donor.waitingCount(), maxLendable, overrideLendable, maxLendable > 0, note, staff,
                    state.modelled ? DepartmentCapacity.utilizationPercent(donor.remainingMinutes, donor.capacityStaff) : null,
                    state.modelled ? DepartmentCapacity.utilizationPercent(donor.remainingMinutes, donor.capacityStaff - 1) : null,
                    state.modelled ? donor.capacityStaff : null));
        }

        Options options = new Options(summary, tasks, allowedHelpers(receiving, state), donors, limitations());
        return managerCodes == null ? options : redactOptionsForManager(options, managerCodes);
    }

    /**
     * A Manager sees who could help (department, person's name and id, which the preview needs), and the donor department's figures only
     * for a donor department the Manager manages too (free and available counts, waiting tasks, lendable limits, explanation note, and each
     * person's task count, minutes and workload status, exactly as an Admin sees them). For every other donor none of that is shown.
     */
    private static Options redactOptionsForManager(Options options, List<String> managedCodes) {
        // A Manager may only lend between departments they manage: a donor department outside that set is not offered at all
        List<DonorDepartment> donors = options.donors().stream().filter(d -> managedCodes.contains(d.code())).toList();
        return new Options(options.receiving(), options.tasks(), options.maxHelpers(), donors, options.limitations());
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

    /** {@code shared}: the helper joins a task its own staff already hold (so the task keeps its status and nothing moves between waiting and assigned). */
    private record Pair(Staff staff, Task task, boolean shared) {}

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
        /** The code of the donor department each reserve problem or warning is about (so a Manager's preview can show it for managed donors only). */
        final Map<Problem, String> donorOf = new HashMap<>();
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
            // A task that passed the checks and is already held is a shared one: only a held task may be accepted, and only to be joined
            if (ok) resolved.pairs.add(new Pair(staff, task, state.assignedTasks.contains(task.getId())));
        }

        int allowed = allowedHelpers(receiving, state);
        if (resolved.pairs.size() > allowed) {
            int uncovered = Math.max(0, receiving.waiting.size() - receiving.free.size());
            String why = allowed == 0
                    ? receiving.department.getName() + " has no task its own free staff cannot take and is not at or above its capacity limit, so it does not need helpers."
                    : uncovered >= allowed
                    ? receiving.department.getName() + " has " + uncovered + " task" + (uncovered == 1 ? "" : "s") + " its own free staff cannot take, so it can use at most "
                            + allowed + " helper" + (allowed == 1 ? "" : "s") + "."
                    : receiving.department.getName() + " needs " + allowed + " more person" + (allowed == 1 ? "" : "s") + " to get below its capacity limit, so it can use at most "
                            + allowed + " helper" + (allowed == 1 ? "" : "s") + ".";
            resolved.problems.add(new Problem("NOT_NEEDED", why, null, null));
        }

        for (Pair pair : resolved.pairs) resolved.lendByDonor.merge(pair.staff().getDepartment().getCode(), 1, Integer::sum);
        for (Map.Entry<String, Integer> lend : resolved.lendByDonor.entrySet()) {
            Stats donor = state.byCode.get(lend.getKey());
            if (donor == null) continue;
            int ownWork = donor.waitingCount();
            int required = Math.max(1, ownWork);
            int freeAfter = donor.free.size() - lend.getValue();
            if (freeAfter < required) {
                // Only the one-person reserve can be acknowledged away: the donor's own waiting work must stay covered
                boolean acknowledgeable = freeAfter >= ownWork;
                if (acknowledgeable && resolved.acknowledged) {
                    resolved.overriddenDonors.add(lend.getKey());
                    Problem warning = new Problem("RESERVE_OVERRIDE", donor.department.getName() + " will be left with " + freeAfter
                            + " free staff, below the " + required + " it normally keeps in reserve. Acknowledged by the manager.", null, null);
                    resolved.warnings.add(warning);
                    resolved.donorOf.put(warning, lend.getKey());
                } else {
                    if (!acknowledgeable) resolved.reserveHardBlocked = true;
                    Problem reserve = new Problem("DONOR_RESERVE", donor.department.getName() + " would be left with " + Math.max(0, freeAfter)
                            + " free staff; it must keep at least " + required + " (a free person in reserve and enough for its own "
                            + ownWork + " waiting task" + (ownWork == 1 ? "" : "s") + ").", null, null);
                    resolved.problems.add(reserve);
                    resolved.donorOf.put(reserve, lend.getKey());
                }
            }
            // Capacity rule, never overridable: the donor must itself stay below its limit with these people gone, or the plan would only move
            // the overload from one department to another
            if (state.modelled && DepartmentCapacity.exceedsLimit(donor.remainingMinutes, donor.capacityStaff - lend.getValue(), state.risk)) {
                resolved.reserveHardBlocked = true;
                Double after = DepartmentCapacity.utilizationPercent(donor.remainingMinutes, donor.capacityStaff - lend.getValue());
                Problem overload = new Problem("DONOR_OVERLOAD", donor.department.getName() + " would be "
                        + (after == null ? "left with work and nobody to do it" : "at " + pct(after) + "% of its capacity")
                        + " after lending " + lend.getValue() + " person" + (lend.getValue() == 1 ? "" : "s") + ", at or above its limit of " + limitText(state) + ".", null, null);
                resolved.problems.add(overload);
                resolved.donorOf.put(overload, lend.getKey());
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
        if (home != null && state.allowedCodes != null && !state.allowedCodes.contains(home)) {
            resolved.problems.add(new Problem("DONOR_NOT_AUTHORIZED", "A selected staff member does not belong to a department you manage, so they cannot be lent.", staff.getId(), item.getTaskId()));
            return false;
        }
        if (home == null) {
            // A Staff member with no department has no home department to lend from: never a support candidate
            resolved.problems.add(new Problem("STAFF_NO_DEPARTMENT", name + " has no department, so they cannot be lent to another department.", staff.getId(), item.getTaskId()));
            ok = false;
        } else if (!state.byCode.containsKey(home)) {
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
        boolean held = state.assignedTasks.contains(task.getId());
        if (!held && task.getStatus() == TaskStatus.PENDING) return true;
        // Option A: a task a Staff member accepted themselves belongs to them alone, so no helper may be added to it
        if (held && assignmentRepository.existsByTaskIdAndSelfClaimedTrue(task.getId())) {
            resolved.problems.add(new Problem("TASK_SELF_CLAIMED", title + " was accepted by a staff member and belongs to them alone, so no helper can join it.", item.getStaffId(), task.getId()));
            return false;
        }
        // A task its own staff already hold may take ONE helper, but only while the department is at or above its capacity limit and the task has none
        if (held && WorkloadRecommendationEngine.canShare(task) && atRisk(receiving, state)
                && supportRepository.findByTaskIdAndStatus(task.getId(), SupportStatus.ACTIVE).isEmpty()) {
            return true;
        }
        resolved.problems.add(new Problem("TASK_ALREADY_ASSIGNED", title + " is already assigned.", item.getStaffId(), task.getId()));
        return false;
    }

    // ── preview ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Preview preview(SupportPlanRequest request) {
        List<String> managerCodes = readerManagedCodes();
        requireCrossDepartmentAssistanceEnabled();
        String requested = request.getReceivingDepartment() == null ? "" : request.getReceivingDepartment().trim();
        if (managerCodes != null) {
            if (!managerCodes.contains(requested)) {
                // Another department as receiver is answered exactly like a department that does not exist (same body, nothing else loaded)
                Resolved notFound = new Resolved();
                notFound.problems.add(new Problem("DEPARTMENT_NOT_FOUND", "The receiving department does not exist or is not active.", null, null));
                return buildPreview(null, notFound);
            }
        }
        State state = managerCodes == null ? load()
                : loadForReceiver(requested, request.getItems().stream().map(SupportPlanRequest.Item::getStaffId).filter(java.util.Objects::nonNull).toList(), managerCodes);
        if (managerCodes != null) state.allowedCodes = new HashSet<>(managerCodes);
        Resolved resolved = resolve(request, state);
        Preview preview = buildPreview(state, resolved);
        return managerCodes == null ? preview : redactPreviewForManager(preview, state, requested, managerCodes, resolved);
    }

    /**
     * A Manager's preview keeps the receiving department's own impact, the valid pairs and the stale-plan fingerprint. About donor departments
     * it shows donor impact figures, the reserve explanation and the override hint only for donors the Manager manages too; for every other
     * donor nothing, and no detail about any person or task that is not a possible helper, a person of a managed department or the receiving
     * department's own task (those problems read as "no longer exists", like a record that is not there).
     */
    private Preview redactPreviewForManager(Preview preview, State state, String ownCode, List<String> managedCodes, Resolved resolved) {
        Set<UUID> visibleStaff = new HashSet<>();
        for (Stats stats : state.byCode.values()) {
            if (stats.department.getCode().equals(ownCode)) continue;
            for (Staff s : stats.lendable) visibleStaff.add(s.getId());
        }
        for (Staff s : state.staffById.values()) {
            if (s.getDepartment() != null && managedCodes.contains(s.getDepartment().getCode())) visibleStaff.add(s.getId());
        }
        Set<UUID> ownWaiting = new HashSet<>();
        Stats own = state.byCode.get(ownCode);
        if (own != null) own.waiting.forEach(t -> ownWaiting.add(t.getId()));

        // A reserve problem or warning is shown in full only when it concerns a donor this Manager manages (recorded when it was raised)
        java.util.function.Predicate<Problem> ofManagedDonor = p -> {
            String donorCode = resolved.donorOf.get(p);
            return donorCode != null && managedCodes.contains(donorCode);
        };
        List<Problem> problems = preview.problems().stream()
                .map(p -> ("DONOR_RESERVE".equals(p.code()) || "DONOR_OVERLOAD".equals(p.code())) && ofManagedDonor.test(p) ? p : redactProblem(p, visibleStaff, ownWaiting)).toList();
        List<Problem> warnings = preview.warnings().stream()
                .map(p -> ofManagedDonor.test(p) ? p : new Problem(p.code(), "The lending department's staffing is tight.", null, null)).toList();
        // The override hint is given only when every reserve problem concerns a donor this Manager manages
        boolean overrideHint = preview.canOverrideReserve() && preview.problems().stream()
                .filter(p -> "DONOR_RESERVE".equals(p.code())).allMatch(ofManagedDonor);
        // preview.donors() already holds the impact of the managed donors only (no other donor's impact is ever computed)
        return new Preview(preview.valid(), problems, warnings, overrideHint, preview.items(), preview.receiving(), preview.donors(), preview.fingerprint(), preview.limitations());
    }

    private static Problem redactProblem(Problem p, Set<UUID> visibleStaff, Set<UUID> ownWaiting) {
        switch (p.code()) {
            case "DONOR_RESERVE": case "DONOR_OVERLOAD":
                return new Problem("DONOR_RESERVE", "The lending department cannot spare this person right now.", null, null);
            case "TASK_WRONG_DEPARTMENT":
                return new Problem("TASK_NOT_FOUND", "A selected task no longer exists.", p.staffId(), p.taskId());
            case "DUPLICATE_TASK":
                return ownWaiting.contains(p.taskId()) ? p : new Problem("TASK_NOT_FOUND", "A selected task no longer exists.", p.staffId(), p.taskId());
            case "STAFF_INACTIVE": case "STAFF_UNAVAILABLE": case "NOT_STAFF_ROLE": case "CANNOT_SIGN_IN": case "STAFF_BUSY":
            case "DONOR_INACTIVE": case "DUPLICATE_STAFF": case "SAME_DEPARTMENT":
                return visibleStaff.contains(p.staffId()) ? p : new Problem("STAFF_NOT_FOUND", "A selected staff member no longer exists.", p.staffId(), p.taskId());
            default:
                return p;
        }
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
        // A helper who joins a held task adds no minutes: they are already counted as assigned
        int addedMinutes = resolved.pairs.stream().filter(p -> !p.shared()).mapToInt(p -> minutes(p.task())).sum();
        DepartmentImpact receivingImpact = new DepartmentImpact("RECEIVING", receiving.department.getCode(), receiving.department.getName(),
                metrics(receiving, state), metrics(receiving, state, remaining, receiving.free.size(), receiving.assignedMinutes + addedMinutes,
                        receiving.capacityStaff + resolved.pairs.size()), 0, resolved.pairs.size());

        List<DepartmentImpact> donors = new ArrayList<>();
        for (Map.Entry<String, Integer> lend : resolved.lendByDonor.entrySet()) {
            Stats donor = state.byCode.get(lend.getKey());
            // A Manager's response carries donor impact only for donors the Manager manages; for any other donor none is computed
            if (donor == null || (state.narrowed && !state.managedDonors.contains(lend.getKey()))) continue;
            // The lent person's task minutes are counted under the receiving department, so the donor's minutes do not change
            donors.add(new DepartmentImpact("DONOR", donor.department.getCode(), donor.department.getName(), metrics(donor, state),
                    metrics(donor, state, donor.waiting, Math.max(0, donor.free.size() - lend.getValue()), donor.assignedMinutes, donor.capacityStaff - lend.getValue()),
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
            sb.append(resolved.receiving.department.getCode()).append('|').append(resolved.receiving.free.size()).append('|').append(resolved.receiving.waiting.size())
                    .append('|').append(resolved.receiving.remainingMinutes).append('|').append(resolved.receiving.capacityStaff).append(';');
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
            if (donor != null) sb.append(lend.getKey()).append('|').append(donor.free.size()).append('|').append(donor.waitingCount())
                    .append('|').append(donor.remainingMinutes).append('|').append(donor.capacityStaff).append(';');
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
        // Admin: any departments. Manager: only between departments they manage (primary + active Admin grants), decided from the database
        // before anything is locked. Staff, an invalid Manager and an unauthenticated caller are refused by the resolver, whatever the HTTP rules say.
        List<String> managerCodes = readerManagedCodes();
        Set<String> allowedCodes = managerCodes == null ? null : new HashSet<>(managerCodes);
        String receivingCode = request.getReceivingDepartment() == null ? "" : request.getReceivingDepartment().trim();
        if (allowedCodes != null) {
            Set<UUID> requestedStaff = request.getItems().stream().map(SupportPlanRequest.Item::getStaffId).collect(Collectors.toSet());
            if (!allowedCodes.contains(receivingCode) || !allowedCodes.containsAll(staffRepository.departmentCodesOf(requestedStaff))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only balance workload between departments you manage.");
            }
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to confirm support");
        }
        String username = auth.getName();
        requireCrossDepartmentAssistanceEnabled();

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
        state.allowedCodes = allowedCodes; // re-checked on the locked data: every donor department must still be managed
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

        // Workload alert emails: the helpers who are about to take the tasks (before anything is created)
        if (workloadAlerts != null) workloadAlerts.track(resolved.pairs.stream().map(pair -> pair.staff().getId()).toList());
        UUID planId = UUID.randomUUID();
        UUID approverId = userRepository.findByUsername(username).map(User::getId).orElse(null);
        List<SupportAssignment> created = new ArrayList<>();
        try {
            for (Pair pair : resolved.pairs) {
                Assignment assignment = new Assignment();
                assignment.setTask(pair.task());
                assignment.setStaff(pair.staff());
                assignment.setAssignedByUserId(approverId); // whoever confirmed the plan (Admin or authorized Manager) is the original assigner
                assignment = assignmentRepository.save(assignment);

                // A waiting task becomes assigned; a task its own staff already hold keeps its status and owner
                if (!pair.shared()) {
                    pair.task().setStatus(TaskStatus.ASSIGNED);
                    taskRepository.save(pair.task());
                }

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
        if (taskEmails != null) {
            // The helpers who just took each task, the Manager(s) of the task's own department and this Admin are told (once each, after commit)
            Map<UUID, List<Pair>> byTask = new java.util.LinkedHashMap<>();
            for (Pair pair : resolved.pairs) byTask.computeIfAbsent(pair.task().getId(), k -> new ArrayList<>()).add(pair);
            for (List<Pair> pairs : byTask.values()) {
                taskEmails.taskAssigned(pairs.get(0).task(), pairs.stream().map(Pair::staff).toList(), username);
            }
        }
        if (!resolved.overriddenDonors.isEmpty()) {
            // The override is reported in the response and logged; storing it would need a schema change (proposed separately)
            log.warn("Support reserve override: {} acknowledged leaving {} below its reserve (plan {}, receiving {})",
                    username, resolved.overriddenDonors, planId, resolved.receiving.department.getCode());
        }
        return withStaffPictures(created.stream()
                .map(s -> SupportAssignmentDto.from(s, null, resolved.overriddenDonors.contains(s.getHomeDepartmentCode())))
                .toList());
    }

    // ── history ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public List<SupportAssignmentDto> list(SupportStatus status) {
        List<String> managerCodes = readerManagedCodes();
        // An Admin's read reconciles every active record (existing behaviour). A Manager's read only reconciles the records that involve the
        // departments they manage, so reading the history can never change another department's support records.
        reconcile(managerCodes == null ? supportRepository.findByStatus(SupportStatus.ACTIVE)
                : supportRepository.findByStatusInvolvingDepartments(SupportStatus.ACTIVE, managerCodes));
        if (managerCodes != null) return listForManager(managerCodes, status);
        // Archived records stay in the database as an audit trail but are no longer listed
        List<SupportAssignment> rows = status == null
                ? supportRepository.findAllByArchivedAtIsNullOrderByCreatedAtDesc()
                : supportRepository.findByStatusAndArchivedAtIsNullOrderByCreatedAtDesc(status);
        Map<UUID, TaskStatus> taskStatuses = new HashMap<>();
        taskRepository.findAllById(rows.stream().filter(r -> r.getStatus() == SupportStatus.ACTIVE).map(SupportAssignment::getTaskId).toList())
                .forEach(task -> taskStatuses.put(task.getId(), task.getStatus()));
        return withStaffPictures(rows.stream().map(row -> SupportAssignmentDto.from(row, taskStatuses.get(row.getTaskId()))).toList());
    }

    /**
     * A Manager's history: only records involving the departments they manage, selected in the database. Where one of those departments
     * received the support the details are shown (without the approver); where it only lent the person, only the person's name and the
     * temporary loan are shown.
     */
    private List<SupportAssignmentDto> listForManager(List<String> codes, SupportStatus status) {
        List<SupportAssignment> rows = status == null
                ? supportRepository.findHistoryInvolvingDepartments(codes)
                : supportRepository.findHistoryInvolvingDepartmentsAndStatus(codes, status);
        Map<UUID, TaskStatus> taskStatuses = new HashMap<>();
        taskRepository.findAllById(rows.stream().filter(r -> r.getStatus() == SupportStatus.ACTIVE && codes.contains(r.getReceivingDepartmentCode()))
                        .map(SupportAssignment::getTaskId).toList())
                .forEach(task -> taskStatuses.put(task.getId(), task.getStatus()));
        return withStaffPictures(rows.stream()
                .map(row -> codes.contains(row.getReceivingDepartmentCode())
                        ? SupportAssignmentDto.forReceivingManager(row, taskStatuses.get(row.getTaskId()))
                        : SupportAssignmentDto.forDonorManager(row))
                .toList());
    }

    // ── removing an ended record from the history ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SupportAssignmentDto archive(UUID supportId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to remove support history");
        }
        // Admin: any record. A valid Manager: only a record involving a department they manage, looked up and locked by that filter, so
        // another department's record is never loaded and answers exactly like one that does not exist. Staff and invalid Managers are refused.
        List<String> managerCodes = readerManagedCodes();
        // Only this record is touched: no task, assignment, staff or department is read for change
        SupportAssignment support = (managerCodes == null ? supportRepository.lockById(supportId)
                : supportRepository.lockByIdInvolvingDepartments(supportId, managerCodes)).orElseThrow(() -> supportNotFound());
        if (support.getStatus() == SupportStatus.ACTIVE) {
            throw new SupportPlanException(HttpStatus.CONFLICT, "This support is still active. End it first, then remove it from the history.",
                    List.of(new Problem("SUPPORT_ACTIVE", "Active support cannot be removed from the history.", support.getStaffId(), taskIdFor(support, managerCodes))));
        }
        if (support.getArchivedAt() != null) {
            throw new SupportPlanException(HttpStatus.CONFLICT, "This record has already been removed from the history. Refresh to see the latest.",
                    List.of(new Problem("ALREADY_ARCHIVED", "This record was already removed from the history.", support.getStaffId(), taskIdFor(support, managerCodes))));
        }
        support.archive(auth.getName(), LocalDateTime.now());
        return withStaffPicture(view(supportRepository.saveAndFlush(support), null, managerCodes));
    }

    /**
     * The rows with each supported person's existing profile picture (the same URL the Staff and Users lists show), looked up once for the
     * people in the list. Only the picture is added: which rows and fields each caller sees is decided before this, unchanged.
     */
    private List<SupportAssignmentDto> withStaffPictures(List<SupportAssignmentDto> rows) {
        Set<UUID> ids = rows.stream().map(SupportAssignmentDto::staffId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return rows;
        Map<UUID, String> pictures = new HashMap<>();
        for (Staff member : staffRepository.findAllById(ids)) {
            if (member.getUser() != null && member.getUser().getProfileImageUrl() != null) pictures.put(member.getId(), member.getUser().getProfileImageUrl());
        }
        return rows.stream().map(row -> row.withStaffProfileImageUrl(pictures.get(row.staffId()))).toList();
    }

    private SupportAssignmentDto withStaffPicture(SupportAssignmentDto row) {
        return withStaffPictures(List.of(row)).get(0);
    }

    /** The record as the caller may see it: everything for an Admin, the receiving or donor projection (never the approver) for a Manager. */
    private static SupportAssignmentDto view(SupportAssignment support, TaskStatus taskStatus, List<String> managerCodes) {
        if (managerCodes == null) return SupportAssignmentDto.from(support, taskStatus);
        return managerCodes.contains(support.getReceivingDepartmentCode())
                ? SupportAssignmentDto.forReceivingManager(support, taskStatus)
                : SupportAssignmentDto.forDonorManager(support);
    }

    /** A donor-only Manager never learns the receiving department's task id, not even from an error. */
    private static UUID taskIdFor(SupportAssignment support, List<String> managerCodes) {
        return managerCodes != null && !managerCodes.contains(support.getReceivingDepartmentCode()) ? null : support.getTaskId();
    }

    // ── ending support by hand ───────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SupportAssignmentDto end(UUID supportId) {
        // Lock in the same order as the task flows (task, staff, then the support record) and read the record only
        // after the locks, so a request that waited sees what the winner did instead of a stale copy.
        // Admin: any record. A valid Manager: only a record involving their department, found and locked by that filter (another department's
        // record is never loaded and answers like one that does not exist). Staff and invalid Managers are refused before anything is read.
        List<String> managerCodes = readerManagedCodes();
        UUID taskId = (managerCodes == null ? supportRepository.findTaskIdById(supportId)
                : supportRepository.findTaskIdByIdInvolvingDepartments(supportId, managerCodes)).orElseThrow(() -> supportNotFound());
        taskRepository.lockByIds(Set.of(taskId));
        SupportAssignment support = (managerCodes == null ? supportRepository.lockById(supportId)
                : supportRepository.lockByIdInvolvingDepartments(supportId, managerCodes)).orElseThrow(() -> supportNotFound());
        staffRepository.lockByIds(Set.of(support.getStaffId()));

        if (support.getStatus() != SupportStatus.ACTIVE) {
            throw new SupportPlanException(HttpStatus.CONFLICT, "This support has already ended. Refresh to see the latest.",
                    List.of(new Problem("ALREADY_ENDED", "This support has already ended.", support.getStaffId(), taskIdFor(support, managerCodes))));
        }

        LocalDateTime now = LocalDateTime.now();
        Task task = taskRepository.findById(support.getTaskId()).orElse(null);
        if (task == null) {
            support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_DELETED, now);
            return withStaffPicture(view(supportRepository.saveAndFlush(support), null, managerCodes));
        }
        // The task finished or moved meanwhile: record what really happened rather than a manual end
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED
                || task.getDepartment() == null || !task.getDepartment().getCode().equals(support.getReceivingDepartmentCode())) {
            endIfTaskDone(support, task);
            return withStaffPicture(view(support, task.getStatus(), managerCodes));
        }
        if (task.getStatus() == TaskStatus.IN_PROGRESS) {
            // A donor-only Manager is told nothing about the receiving department's task: no title, no id
            boolean donorOnly = managerCodes != null && !managerCodes.contains(support.getReceivingDepartmentCode());
            throw new SupportPlanException(HttpStatus.CONFLICT,
                    donorOnly ? "The task is already in progress. Support cannot be ended until the task is completed or reassigned."
                            : "\"" + task.getTitle() + "\" is already in progress. Support cannot be ended until the task is completed or reassigned.",
                    List.of(new Problem("TASK_IN_PROGRESS", "The task is already in progress.", support.getStaffId(), donorOnly ? null : task.getId())));
        }

        List<Assignment> held = assignmentRepository.findAllByTaskId(task.getId());
        List<Assignment> mine = held.stream().filter(a -> a.getStaff().getId().equals(support.getStaffId())).toList();
        if (held.size() > mine.size() && !mine.isEmpty()) {
            // The helper joined a task its own staff hold: release only the helper; the task keeps its owner and its status
            if (workloadAlerts != null) workloadAlerts.track(List.of(support.getStaffId())); // workload alert emails: before it is released
            assignmentRepository.deleteAll(mine);
            support.end(SupportStatus.CANCELLED, SupportEndReason.MANUALLY_ENDED, now);
        } else if (held.size() > mine.size()) {
            // Someone else holds the task now: nothing to release, the support just no longer applies
            support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_REASSIGNED, now);
        } else {
            // Release the temporary assignment and put the task back in its department's waiting list
            if (workloadAlerts != null) workloadAlerts.track(List.of(support.getStaffId())); // workload alert emails: before it is released
            assignmentRepository.deleteAll(mine);
            task.setStatus(TaskStatus.PENDING);
            taskRepository.save(task);
            support.end(SupportStatus.CANCELLED, SupportEndReason.MANUALLY_ENDED, now);
        }
        supportRepository.saveAndFlush(support);
        return withStaffPicture(view(support, task.getStatus(), managerCodes));
    }

    /** A coded 404, so a client can tell "this record does not exist" from "this server has no such route". */
    private static SupportPlanException supportNotFound() {
        return new SupportPlanException(HttpStatus.NOT_FOUND, "This support record no longer exists. Refresh to see the latest.",
                List.of(new Problem("SUPPORT_NOT_FOUND", "This support record does not exist.", null, null)));
    }

    /** Safety net: closes active support whose task is gone or finished, whatever path changed it. */
    /**
     * Ends the given active records whose task is gone, finished or moved. Each record is judged on its own task only, so reconciling a subset
     * applies exactly the same rules as reconciling all of them.
     */
    private void reconcile(List<SupportAssignment> activeRecords) {
        for (SupportAssignment support : activeRecords) {
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
            // A task can have several holders: the support applies for as long as the SUPPORTED person still holds it, whoever else was
            // reassigned. With one holder this is exactly the earlier rule (the support ends unless the new holder is the supported person).
            boolean supportedStaffStillHolds = support.getStaffId().equals(newStaffId)
                    || assignmentRepository.findAllByTaskId(taskId).stream().anyMatch(a -> a.getStaff() != null && support.getStaffId().equals(a.getStaff().getId()));
            if (!supportedStaffStillHolds) {
                support.end(SupportStatus.CANCELLED, SupportEndReason.TASK_REASSIGNED, LocalDateTime.now());
                supportRepository.save(support);
            }
        });
    }
}
