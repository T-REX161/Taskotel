package com.hotel.workload.workload;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.workload.WorkloadRecommendationsDto.DepartmentRecommendation;
import com.hotel.workload.workload.WorkloadRecommendationsDto.DonorImpact;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Pressure;
import com.hotel.workload.workload.WorkloadRecommendationsDto.ReceiverImpact;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Severity;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Suggestion;
import com.hotel.workload.workload.WorkloadRecommendationsDto.UnsupportedDepartment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The recommendation rules, as pure functions of the data passed in (no database, no clock), so they can be
 * tested directly. WorkloadRecommendationServiceImpl loads the data and calls {@link #recommend}.
 *
 * <p>Vocabulary, all derived from stored data:
 * <ul>
 *   <li><b>waiting task</b>: an active task (pending, assigned or in progress) of the department with no assignment row;</li>
 *   <li><b>free staff</b>: active, marked Available and holding no active assignment. Taskotel lets a staff member
 *       hold one active assignment at a time, so only free staff can take a waiting task;</li>
 *   <li><b>uncovered task</b>: a waiting task left over after the department's own free staff take the most
 *       important ones.</li>
 * </ul>
 *
 * <p>Only some free people may be <b>suggested</b> (lent): an active STAFF account that can actually sign in, meaning its
 * email is verified and its credentials are set up (see AuthService.authenticate, which refuses anyone else a token).
 * Admin and Manager accounts, and staff still waiting for email verification or credentials setup, are never suggested.
 * They still count as free staff of their own department, because they can be assigned tasks, so they still cover its
 * waiting work and can be the free person a lending department keeps in reserve.
 *
 * <p>A department needs support when it has uncovered tasks. Another department may lend a free staff member only if,
 * afterwards, it still has at least one free, available staff member (a reserve, even when nothing is waiting in it)
 * and enough free staff to cover every task already waiting in it. So a department with a single free person never lends them.
 * The engine never reads skills, shifts or capacity: Taskotel does not store them.
 */
public final class WorkloadRecommendationEngine {

    /** A waiting task due today, tomorrow or earlier counts as due soon. */
    public static final int DUE_SOON_DAYS = 1;
    static final int MAX_SUGGESTIONS_PER_DEPARTMENT = 3;

    /** Most important waiting work first: higher priority, then earlier due date, then title. */
    public static final Comparator<Task> MOST_IMPORTANT_FIRST = Comparator
            .comparing((Task t) -> t.getPriority() == null ? Priority.LOW : t.getPriority()).reversed()
            .thenComparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(t -> String.valueOf(t.getTitle()).toLowerCase())
            .thenComparing(t -> String.valueOf(t.getId()));

    private WorkloadRecommendationEngine() {}

    private static final class Stats {
        final Department department;
        final List<Staff> activeStaff = new ArrayList<>();
        final List<Staff> availableStaff = new ArrayList<>();
        final List<Staff> freeStaff = new ArrayList<>();
        /** The free staff who may be suggested to another department (a subset of freeStaff). */
        final List<Staff> lendable = new ArrayList<>();
        final List<Task> waiting = new ArrayList<>();
        /** Tasks its own staff already hold, that a helper may JOIN while the department is at its limit (see canShare). */
        final List<Task> shareable = new ArrayList<>();
        /** Only for a donor department in a receiver-scoped run: how many tasks wait there (its task details are never loaded). */
        Integer waitingCountOverride;
        /** Remaining task minutes and the staff whose capacity counts here (see DepartmentCapacity); only meaningful with capacity inputs. */
        int remainingMinutes;
        int capacityStaff;

        Stats(Department department) {
            this.department = department;
        }

        int waitingCount() {
            return waitingCountOverride != null ? waitingCountOverride : waiting.size();
        }

        int uncovered() {
            return Math.max(0, waiting.size() - freeStaff.size());
        }

        List<Task> uncoveredTasks() {
            return waiting.subList(Math.min(freeStaff.size(), waiting.size()), waiting.size());
        }
    }

    public static WorkloadRecommendationsDto recommend(List<Department> departments,
                                                       List<Staff> staff,
                                                       List<Task> activeTasks,
                                                       List<Assignment> activeAssignments,
                                                       LocalDate today,
                                                       LocalDateTime now,
                                                       CapacityInputs capacity) {
        Set<UUID> busyStaff = new HashSet<>();
        Set<UUID> assignedTasks = new HashSet<>();
        for (Assignment assignment : activeAssignments) {
            if (assignment.getStaff() != null) busyStaff.add(assignment.getStaff().getId());
            if (assignment.getTask() != null) assignedTasks.add(assignment.getTask().getId());
        }

        Map<String, Stats> byCode = new HashMap<>();
        List<Stats> all = new ArrayList<>();
        for (Department department : departments) {
            if (department.isActive()) {
                Stats stats = new Stats(department);
                byCode.put(department.getCode(), stats);
                all.add(stats);
            }
        }

        classifyStaff(byCode, staff, busyStaff);

        int tasksWithoutDepartment = 0;
        for (Task task : activeTasks) {
            if (task.getDepartment() == null) {
                tasksWithoutDepartment++;
                continue;
            }
            Stats stats = byCode.get(task.getDepartment().getCode());
            if (stats != null && !assignedTasks.contains(task.getId())) stats.waiting.add(task);
        }

        applyCapacity(all, capacity);
        return finish(all, null, null, tasksWithoutDepartment, today, now, capacity);
    }

    private static void classifyStaff(Map<String, Stats> byCode, List<Staff> staff, Set<UUID> busyStaff) {
        for (Staff member : staff) {
            Stats stats = member.getDepartment() == null ? null : byCode.get(member.getDepartment().getCode());
            if (stats == null || !member.isActive()) continue;
            stats.activeStaff.add(member);
            if (member.getAvailability() == Availability.AVAILABLE) {
                stats.availableStaff.add(member);
                if (!busyStaff.contains(member.getId())) {
                    stats.freeStaff.add(member);
                    if (canBeSuggested(member)) stats.lendable.add(member);
                }
            }
        }
    }

    /**
     * The recommendation for ONE receiving department (a Manager's own), with nothing about the other departments in the result. The same
     * rules as {@link #recommend}: severity, reserve, lendability and ranking are unchanged. The inputs are deliberately narrow, so the caller
     * never loads other departments' tasks: only the receiver's waiting tasks, the NUMBER of tasks waiting in each other department (the reserve
     * rule needs it), and the ids of staff who hold an active assignment.
     *
     * <p>The result is redacted: a suggestion carries only the person's name and home department name (no id or department code), no donor
     * impact figures, and a neutral reason, so no donor workload, free-staff or task figure can be read from it. The count of tasks without a
     * department is not reported.
     */
    public static WorkloadRecommendationsDto recommendForReceiver(Department receiver,
                                                                  List<Department> departments,
                                                                  List<Staff> staff,
                                                                  List<Task> receiverWaitingTasks,
                                                                  Map<String, Integer> waitingCountByDepartment,
                                                                  Set<UUID> busyStaff,
                                                                  LocalDate today,
                                                                  LocalDateTime now,
                                                                        CapacityInputs capacity) {
        Map<String, Stats> byCode = new HashMap<>();
        List<Stats> all = new ArrayList<>();
        for (Department department : departments) {
            if (department.isActive()) {
                Stats stats = new Stats(department);
                byCode.put(department.getCode(), stats);
                all.add(stats);
            }
        }
        Stats receiving = receiver == null ? null : byCode.get(receiver.getCode());
        if (receiving == null) {
            return new WorkloadRecommendationsDto(now, List.of(), List.of(), limitations(0));
        }
        classifyStaff(byCode, staff, busyStaff);
        for (Stats stats : all) {
            if (stats != receiving) stats.waitingCountOverride = waitingCountByDepartment.getOrDefault(stats.department.getCode(), 0);
        }
        for (Task task : receiverWaitingTasks) {
            if (task.getDepartment() != null && receiving.department.getCode().equals(task.getDepartment().getCode())) receiving.waiting.add(task);
        }
        Set<String> only = Set.of(receiving.department.getCode());
        applyCapacity(all, capacity);
        return finish(all, only, only, 0, today, now, capacity);
    }

    /**
     * The recommendations for EVERY department a Manager manages (their primary department and the active departments an Administrator
     * granted), ranked together exactly like {@link #recommend} ranks every department for an Admin: the same severity, reserve, lendability
     * and ranking rules, and one person is never suggested to two departments. Nothing is generated for any other department as a receiver.
     *
     * <p>What the result shows about a donor depends on whether the Manager manages the donor's department too:
     * <ul>
     *   <li>a managed donor department is shown in full, as an Admin sees it (the person's id and home department code, the donor's before
     *       and after figures, and the reasons), because the Manager may see all of that department's figures anyway;</li>
     *   <li>any other donor is redacted exactly as in {@link #recommendForReceiver}: the person's name and home department name only.</li>
     * </ul>
     * The inputs stay narrow: the waiting tasks of the MANAGED departments only, and for every other department just the NUMBER of tasks
     * waiting there (the reserve rule needs it). The count of tasks without a department is not reported.
     *
     * @param managed                       the departments the Manager manages (inactive ones are ignored)
     * @param waitingTasksByManagedDepartment the waiting tasks of each managed department, by department code
     */
    public static WorkloadRecommendationsDto recommendForManager(java.util.Collection<Department> managed,
                                                                 List<Department> departments,
                                                                 List<Staff> staff,
                                                                 Map<String, List<Task>> waitingTasksByManagedDepartment,
                                                                 Map<String, Integer> waitingCountByDepartment,
                                                                 Set<UUID> busyStaff,
                                                                 LocalDate today,
                                                                 LocalDateTime now,
                                                                       CapacityInputs capacity) {
        Map<String, Stats> byCode = new HashMap<>();
        List<Stats> all = new ArrayList<>();
        for (Department department : departments) {
            if (department.isActive()) {
                Stats stats = new Stats(department);
                byCode.put(department.getCode(), stats);
                all.add(stats);
            }
        }
        Set<String> managedCodes = new HashSet<>();
        for (Department department : managed) {
            // Only a department that is active (it has stats) is a receiver
            if (department != null && byCode.containsKey(department.getCode())) managedCodes.add(department.getCode());
        }
        if (managedCodes.isEmpty()) {
            return new WorkloadRecommendationsDto(now, List.of(), List.of(), limitations(0));
        }
        classifyStaff(byCode, staff, busyStaff);
        for (Stats stats : all) {
            String code = stats.department.getCode();
            if (managedCodes.contains(code)) {
                for (Task task : waitingTasksByManagedDepartment.getOrDefault(code, List.of())) {
                    // A task of another department can never count as this department's waiting work
                    if (task.getDepartment() != null && code.equals(task.getDepartment().getCode())) stats.waiting.add(task);
                }
            } else {
                stats.waitingCountOverride = waitingCountByDepartment.getOrDefault(code, 0);
            }
        }
        applyCapacity(all, capacity);
        return finish(all, managedCodes, managedCodes, 0, today, now, capacity);
    }

    /**
     * Ranks the departments that need help and suggests donors. {@code receiverCodes} null = every department (Admin, nothing redacted);
     * otherwise only those departments are receivers, and a donor whose code is not in {@code detailCodes} is redacted.
     *
     * <p>A department needs help when it has uncovered waiting tasks (more waiting tasks than free staff: the original rule), or, when the
     * capacity inputs are present and the Max Task Capacity Alert is on, when its utilization is at or above the Capacity Threshold even though
     * its own free staff could take every waiting task. A helper always takes a WAITING task (support adds a person to a task nobody holds), so
     * a department at risk with no waiting task is reported as needing help but without anyone to send, and says why.
     *
     * <p>A donor is accepted only if, after lending, it still keeps a free person and enough free staff for its own waiting work (the original
     * reserve rule) AND its own utilization stays below the limit (the threshold when the alert is on, otherwise a full shift per person), so
     * a recommendation never just moves the overload to another department. Projections are computed from the remaining minutes (which do not
     * change when someone helps) and the capacity (the helper's moves from the donor to the receiver).
     */
    private static WorkloadRecommendationsDto finish(List<Stats> all, Set<String> receiverCodes, Set<String> detailCodes,
                                                     int tasksWithoutDepartment, LocalDate today, LocalDateTime now, CapacityInputs capacity) {
        Comparator<Staff> byName = Comparator.comparing((Staff s) -> String.valueOf(s.getName()).toLowerCase()).thenComparing(s -> String.valueOf(s.getId()));
        Comparator<Task> mostImportantFirst = MOST_IMPORTANT_FIRST;
        for (Stats stats : all) {
            stats.freeStaff.sort(byName);
            stats.lendable.sort(byName);
            stats.waiting.sort(mostImportantFirst);
            stats.shareable.sort(mostImportantFirst);
        }

        List<Stats> pressured = new ArrayList<>();
        for (Stats stats : all) {
            boolean inScope = receiverCodes == null || receiverCodes.contains(stats.department.getCode());
            if (inScope && (stats.uncovered() > 0 || atRisk(stats, capacity))) pressured.add(stats);
        }
        pressured.sort(Comparator
                .comparing((Stats s) -> severity(s, today, capacity))
                .thenComparingInt((Stats s) -> -s.uncovered())
                .thenComparingDouble((Stats s) -> -utilizationForSort(s, capacity))
                .thenComparing(s -> s.department.getName().toLowerCase()));

        List<DepartmentRecommendation> recommendations = new ArrayList<>();
        List<UnsupportedDepartment> unsupported = new ArrayList<>();
        boolean modelled = capacity.modelled();

        for (Stats receiver : pressured) {
            boolean riskDriven = atRisk(receiver, capacity);
            Severity severity = severity(receiver, today, capacity);
            Pressure pressure = pressure(receiver, today, capacity);
            List<Task> uncovered = receiver.uncoveredTasks();
            // The tasks a helper could take: the uncovered ones first, then (only for a department at risk) its other waiting tasks, most important first
            List<Task> coverable = new ArrayList<>(uncovered);
            if (riskDriven) {
                for (Task task : receiver.waiting) if (!uncovered.contains(task)) coverable.add(task);
                // Then the tasks its own staff already hold: a helper joins one, adding a person without moving any work
                coverable.addAll(receiver.shareable);
            }
            int helpersForRisk = riskDriven ? DepartmentCapacity.helpersToLeaveRisk(receiver.remainingMinutes, receiver.capacityStaff, capacity.risk()) : 0;
            int wanted = Math.min(Math.min(coverable.size(), MAX_SUGGESTIONS_PER_DEPARTMENT), Math.max(uncovered.size(), helpersForRisk));
            List<Suggestion> suggestions = new ArrayList<>();
            int uncoveredBefore = uncovered.size();

            while (suggestions.size() < wanted) {
                Stats donor = bestDonor(all, receiver, capacity);
                if (donor == null) break;
                Staff chosen = donor.lendable.get(0);
                int index = suggestions.size();
                Task covers = coverable.get(index);
                int minutesAfter = minutes(uncovered.subList(Math.min(index + 1, uncovered.size()), uncovered.size()));
                int freeBefore = donor.freeStaff.size();
                int availableBefore = donor.availableStaff.size();
                Double donorUtilizationBefore = modelled ? DepartmentCapacity.utilizationPercent(donor.remainingMinutes, donor.capacityStaff) : null;
                Double donorUtilizationAfter = modelled ? DepartmentCapacity.utilizationPercent(donor.remainingMinutes, donor.capacityStaff - 1) : null;
                DonorImpact donorImpact = new DonorImpact(freeBefore, freeBefore - 1, availableBefore, availableBefore - 1, donor.waitingCount(),
                        donorUtilizationBefore, donorUtilizationAfter);
                // receiver.capacityStaff already includes the helpers chosen earlier in this run for this department
                Double receiverUtilizationBefore = modelled ? DepartmentCapacity.utilizationPercent(receiver.remainingMinutes, receiver.capacityStaff) : null;
                Double receiverUtilizationAfter = modelled ? DepartmentCapacity.utilizationPercent(receiver.remainingMinutes, receiver.capacityStaff + 1) : null;
                Boolean stillAtRisk = modelled ? DepartmentCapacity.atRisk(receiver.remainingMinutes, receiver.capacityStaff + 1, capacity.risk()) : null;
                int uncoveredHere = Math.max(0, uncoveredBefore - index);
                ReceiverImpact receiverImpact = new ReceiverImpact(
                        uncoveredHere, Math.max(0, uncoveredHere - 1),
                        minutes(uncovered.subList(Math.min(index, uncovered.size()), uncovered.size())), minutesAfter,
                        receiverUtilizationBefore, receiverUtilizationAfter, stillAtRisk);
                if (receiverCodes != null && !detailCodes.contains(donor.department.getCode())) {
                    // Only the minimum a Manager needs to judge a helper: name and home department name. Nothing about the donor's workload.
                    List<String> reasons = new ArrayList<>();
                    // Only neutral reasons: the receiving department's own projection travels in receiverImpact, and nothing about the donor
                    reasons.add("Currently free and available to help.");
                    reasons.add(couldTakeReason(covers));
                    suggestions.add(new Suggestion(null, chosen.getName(), null, donor.department.getName(), covers.getTitle(), null, receiverImpact, reasons));
                } else {
                    suggestions.add(new Suggestion(chosen.getId(), chosen.getName(), donor.department.getCode(), donor.department.getName(),
                            covers.getTitle(), donorImpact, receiverImpact,
                            suggestionReasons(donor, freeBefore, covers, receiver, donorUtilizationAfter, receiverUtilizationBefore, receiverUtilizationAfter, stillAtRisk, capacity)));
                }
                // The chosen person leaves the lending department's lists, so later receivers see what is really left
                donor.lendable.remove(chosen);
                donor.freeStaff.remove(chosen);
                donor.availableStaff.remove(chosen);
                if (modelled) {
                    donor.capacityStaff--;
                    receiver.capacityStaff++;
                }
            }

            if (suggestions.isEmpty()) {
                unsupported.add(new UnsupportedDepartment(receiver.department.getCode(), receiver.department.getName(), severity, pressure,
                        noHelperReason(coverable, uncovered, riskDriven)));
            } else {
                recommendations.add(new DepartmentRecommendation(receiver.department.getCode(), receiver.department.getName(),
                        severity, pressure, receiverReasons(receiver, pressure, capacity), suggestions));
            }
        }

        return new WorkloadRecommendationsDto(now, recommendations, unsupported,
                limitations(tasksWithoutDepartment));
    }

    private static String noHelperReason(List<Task> coverable, List<Task> uncovered, boolean riskDriven) {
        if (coverable.isEmpty() && uncovered.isEmpty() && riskDriven) {
            return "At or above its limit, but it has no active task a helper could join.";
        }
        return "No suitable support is available: no other department can lend a free person without being left short or over its own limit.";
    }

    /** A task a helper may join: its own staff hold it and it is under way or about to be (assigned or in progress). */
    public static boolean canShare(Task task) {
        return task != null && (task.getStatus() == com.hotel.workload.task.TaskStatus.ASSIGNED || task.getStatus() == com.hotel.workload.task.TaskStatus.IN_PROGRESS);
    }

    private static boolean atRisk(Stats stats, CapacityInputs capacity) {
        return capacity.modelled() && DepartmentCapacity.atRisk(stats.remainingMinutes, stats.capacityStaff, capacity.risk());
    }

    private static double utilizationForSort(Stats stats, CapacityInputs capacity) {
        if (!capacity.modelled()) return 0;
        Double utilization = DepartmentCapacity.utilizationPercent(stats.remainingMinutes, stats.capacityStaff);
        return utilization == null ? Double.MAX_VALUE : utilization;
    }

    private static void applyCapacity(List<Stats> all, CapacityInputs capacity) {
        if (!capacity.modelled()) return;
        for (Stats stats : all) {
            stats.remainingMinutes = capacity.remainingMinutes().getOrDefault(stats.department.getCode(), 0);
            stats.capacityStaff = capacity.eligibleStaff().getOrDefault(stats.department.getCode(), 0);
            stats.shareable.clear();
            stats.shareable.addAll(capacity.shareable().getOrDefault(stats.department.getCode(), List.of()));
        }
    }

    /**
     * The department that can best lend one more person: after lending it must still have at least one free, available staff member and enough
     * free staff for every task waiting in it, and (with capacity inputs) its own utilization must stay below the limit with one person fewer.
     * A person already suggested to another department has been removed from its lists and its capacity already reduced.
     */
    private static Stats bestDonor(List<Stats> all, Stats receiver, CapacityInputs capacity) {
        Stats best = null;
        int bestSpare = Integer.MIN_VALUE;
        for (Stats candidate : all) {
            if (candidate == receiver) continue;
            int free = candidate.freeStaff.size();
            int freeAfter = free - 1;
            // Reserve rule: at least one free person must remain, whether or not anything is waiting
            if (candidate.lendable.isEmpty() || freeAfter < Math.max(1, candidate.waitingCount())) continue;
            // Capacity rule: lending must not push the donor itself to or past its limit (that would only move the overload)
            if (capacity.modelled() && DepartmentCapacity.exceedsLimit(candidate.remainingMinutes, candidate.capacityStaff - 1, capacity.risk())) continue;
            int spare = freeAfter - candidate.waitingCount();
            if (spare > bestSpare || (spare == bestSpare && best != null
                    && candidate.department.getName().compareToIgnoreCase(best.department.getName()) < 0)) {
                best = candidate;
                bestSpare = spare;
            }
        }
        return best;
    }

    private static Severity severity(Stats stats, LocalDate today) {
        boolean critical = false;
        boolean high = false;
        for (Task task : stats.uncoveredTasks()) {
            if (task.getPriority() == Priority.URGENT || (task.getDueDate() != null && task.getDueDate().isBefore(today))) critical = true;
            if (task.getPriority() == Priority.HIGH || (task.getDueDate() != null && !task.getDueDate().isAfter(today.plusDays(DUE_SOON_DAYS)))) high = true;
        }
        return critical ? Severity.CRITICAL : high ? Severity.HIGH : Severity.MODERATE;
    }

    /** As above, and a department that is at risk with more work than a full shift of its people can do is at least HIGH. */
    private static Severity severity(Stats stats, LocalDate today, CapacityInputs capacity) {
        Severity base = severity(stats, today);
        if (base == Severity.MODERATE && atRisk(stats, capacity) && DepartmentCapacity.atOrAbove(stats.remainingMinutes, stats.capacityStaff, 100)) return Severity.HIGH;
        return base;
    }

    private static Pressure pressure(Stats stats, LocalDate today, CapacityInputs capacity) {
        int urgentOrHigh = 0;
        int dueSoon = 0;
        int noDuration = 0;
        for (Task task : stats.waiting) {
            if (task.getPriority() == Priority.URGENT || task.getPriority() == Priority.HIGH) urgentOrHigh++;
            if (task.getDueDate() != null && !task.getDueDate().isAfter(today.plusDays(DUE_SOON_DAYS))) dueSoon++;
            if (task.getEstimatedDuration() == null) noDuration++;
        }
        boolean modelled = capacity.modelled();
        boolean riskDriven = atRisk(stats, capacity);
        String need = stats.uncovered() > 0 ? (riskDriven ? "BOTH" : "UNCOVERED_TASKS") : "HIGH_UTILIZATION";
        return new Pressure(stats.waiting.size(), minutes(stats.waiting), urgentOrHigh, dueSoon,
                stats.activeStaff.size(), stats.availableStaff.size(), stats.freeStaff.size(),
                stats.uncovered(), minutes(stats.uncoveredTasks()), noDuration,
                modelled ? stats.remainingMinutes : 0, modelled ? stats.capacityStaff : 0, modelled ? DepartmentCapacity.capacityMinutes(stats.capacityStaff) : 0,
                modelled ? DepartmentCapacity.utilizationPercent(stats.remainingMinutes, stats.capacityStaff) : null,
                modelled ? DepartmentCapacity.level(stats.remainingMinutes, stats.capacityStaff, capacity.risk()) : null,
                riskDriven, modelled && DepartmentCapacity.noCapacity(stats.remainingMinutes, stats.capacityStaff), need);
    }

    /**
     * Whether this person may be suggested: an active STAFF account that can sign in. AuthService.authenticate refuses
     * a STAFF account until its email is verified and its credentials are set up, and refuses disabled accounts.
     */
    public static boolean canBeSuggested(Staff member) {
        User account = member.getUser();
        return account != null
                && account.getRole() == Role.STAFF
                && account.isActive()
                && account.isEmailVerified()
                && !account.isCredentialsSetupRequired();
    }

    private static int minutes(List<Task> tasks) {
        int total = 0;
        for (Task task : tasks) total += Objects.requireNonNullElse(task.getEstimatedDuration(), 0);
        return total;
    }

    private static String pct(Double value) {
        if (value == null) return "n/a";
        return value == Math.rint(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    private static String limitText(CapacityInputs capacity) {
        return capacity.risk().enabled() ? capacity.risk().thresholdPercent() + "%" : "a full shift per person";
    }

    private static String receiverProjection(Stats receiver, Double before, Double after, Boolean stillAtRisk, CapacityInputs capacity) {
        return "With this person " + receiver.department.getName() + " goes from " + pct(before) + "% to " + pct(after) + "% of its capacity ("
                + (Boolean.TRUE.equals(stillAtRisk) ? "still at or above" : "below") + " the " + limitText(capacity) + " limit).";
    }

    private static List<String> receiverReasons(Stats stats, Pressure p, CapacityInputs capacity) {
        List<String> reasons = new ArrayList<>();
        if (p.uncoveredTasks() > 0) {
            reasons.add(plural(p.waitingTasks(), "task") + " waiting for someone (" + p.waitingMinutes() + " min) and "
                    + p.freeStaff() + " available staff with no active task, so " + plural(p.uncoveredTasks(), "task")
                    + " (" + p.uncoveredMinutes() + " min) cannot be picked up by the department's own staff.");
        } else {
            reasons.add("Its own free staff could take its " + plural(p.waitingTasks(), "waiting task") + ", but its workload is too high for the staff it has.");
        }
        if (p.atRisk()) {
            if (p.noCapacity()) {
                reasons.add("It has " + p.remainingMinutes() + " min of work and no eligible staff to do it.");
            } else {
                reasons.add("Utilization is " + pct(p.utilizationPercent()) + "%: " + p.remainingMinutes() + " min of work against " + p.capacityMinutes()
                        + " min of capacity (" + plural(p.eligibleStaff(), "eligible person") + " x " + DepartmentCapacity.STANDARD_SHIFT_MINUTES
                        + " min), at or above the " + limitText(capacity) + " limit.");
            }
        }
        if (p.urgentOrHighTasks() > 0) reasons.add(plural(p.urgentOrHighTasks(), "waiting task") + " with High or Urgent priority.");
        if (p.overdueOrDueSoonTasks() > 0) reasons.add(plural(p.overdueOrDueSoonTasks(), "waiting task") + " overdue or due within a day.");
        if (p.tasksWithoutDuration() > 0) {
            reasons.add(plural(p.tasksWithoutDuration(), "waiting task") + " with no estimated duration, counted as 0 minutes.");
        }
        return reasons;
    }

    private static List<String> suggestionReasons(Stats donor, int freeBefore, Task covers, Stats receiver, Double donorUtilizationAfter,
                                                  Double receiverUtilizationBefore, Double receiverUtilizationAfter, Boolean stillAtRisk, CapacityInputs capacity) {
        List<String> reasons = new ArrayList<>();
        reasons.add(donor.department.getName() + " has " + freeBefore + " available staff with no active task and "
                + plural(donor.waitingCount(), "waiting task") + "; lending one leaves " + (freeBefore - 1)
                + " free, so it keeps a free person in reserve and still covers its own waiting work.");
        if (capacity.modelled()) {
            reasons.add(donor.department.getName() + " stays at " + pct(donorUtilizationAfter) + "% of its capacity after lending, below the "
                    + limitText(capacity) + " limit.");
            reasons.add(receiverProjection(receiver, receiverUtilizationBefore, receiverUtilizationAfter, stillAtRisk, capacity));
        }
        reasons.add(couldTakeReason(covers));
        return reasons;
    }

    private static String couldTakeReason(Task covers) {
        String detail = (covers.getPriority() == null ? "" : covers.getPriority() + " priority, ")
                + (covers.getEstimatedDuration() == null ? "no estimated duration" : covers.getEstimatedDuration() + " min")
                + (covers.getDueDate() == null ? "" : ", due " + covers.getDueDate());
        if (covers.getStatus() != com.hotel.workload.task.TaskStatus.PENDING) {
            return "Could join \"" + covers.getTitle() + "\" (" + detail + "), a task its own staff already hold, if support were arranged.";
        }
        return "Could take \"" + covers.getTitle() + "\" (" + detail + ") if support were arranged.";
    }

    private static List<String> limitations(int tasksWithoutDepartment) {
        List<String> limitations = new ArrayList<>();
        limitations.add("Skills are not recorded in Taskotel, so a person's suitability for the receiving department's work is not assessed. A manager must judge it.");
        limitations.add("Only active STAFF accounts that can sign in (email verified, credentials set up) are suggested. Managers, admins and staff still waiting for email verification or credentials setup are never suggested, although they still count as free staff of their own department.");
        limitations.add("Shifts and working hours are not recorded. \"Available\" means the person is active, marked Available and has no active task right now.");
        limitations.add("Suggestions are never applied automatically: nothing moves staff or tasks until an Administrator confirms a support plan, which lends the person to the receiving department until the task is completed or the support is ended.");
        if (tasksWithoutDepartment > 0) {
            limitations.add(plural(tasksWithoutDepartment, "active task") + " with no department " + (tasksWithoutDepartment == 1 ? "is" : "are") + " not counted.");
        }
        return limitations;
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * Each department's remaining task minutes and capacity staff (see DepartmentCapacity) plus the risk rule: what the engine needs to judge
     * departments by capacity. {@link #NONE} means "no capacity model": only the original uncovered-task rule applies.
     */
    public record CapacityInputs(boolean modelled, Map<String, Integer> remainingMinutes, Map<String, Integer> eligibleStaff, DepartmentCapacity.Risk risk,
                                 Map<String, List<Task>> shareable) {
        public static final CapacityInputs NONE = new CapacityInputs(false, Map.of(), Map.of(), DepartmentCapacity.Risk.OFF, Map.of());

        public CapacityInputs(boolean modelled, Map<String, Integer> remainingMinutes, Map<String, Integer> eligibleStaff, DepartmentCapacity.Risk risk) {
            this(modelled, remainingMinutes, eligibleStaff, risk, Map.of());
        }

        /** The same inputs with the tasks (by department code) a helper may join: held by the department's own staff and without a helper yet. */
        public CapacityInputs withShareable(Map<String, List<Task>> shareable) {
            return new CapacityInputs(modelled, remainingMinutes, eligibleStaff, risk, shareable == null ? Map.of() : shareable);
        }

        public static CapacityInputs of(Map<String, Integer> remainingMinutes, Map<String, Integer> eligibleStaff, DepartmentCapacity.Risk risk) {
            return new CapacityInputs(true, remainingMinutes, eligibleStaff, risk == null ? DepartmentCapacity.Risk.OFF : risk);
        }
    }

    // The original signatures: no capacity model, so only the uncovered-task rule applies (unchanged behaviour)
    public static WorkloadRecommendationsDto recommend(List<Department> departments, List<Staff> staff, List<Task> activeTasks,
                                                       List<Assignment> activeAssignments, LocalDate today, LocalDateTime now) {
        return recommend(departments, staff, activeTasks, activeAssignments, today, now, CapacityInputs.NONE);
    }

    public static WorkloadRecommendationsDto recommendForReceiver(Department receiver, List<Department> departments, List<Staff> staff,
                                                                  List<Task> receiverWaitingTasks, Map<String, Integer> waitingCountByDepartment,
                                                                  Set<UUID> busyStaff, LocalDate today, LocalDateTime now) {
        return recommendForReceiver(receiver, departments, staff, receiverWaitingTasks, waitingCountByDepartment, busyStaff, today, now, CapacityInputs.NONE);
    }

    public static WorkloadRecommendationsDto recommendForManager(java.util.Collection<Department> managed, List<Department> departments, List<Staff> staff,
                                                                 Map<String, List<Task>> waitingTasksByManagedDepartment, Map<String, Integer> waitingCountByDepartment,
                                                                 Set<UUID> busyStaff, LocalDate today, LocalDateTime now) {
        return recommendForManager(managed, departments, staff, waitingTasksByManagedDepartment, waitingCountByDepartment, busyStaff, today, now, CapacityInputs.NONE);
    }
}
