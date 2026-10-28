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

        Stats(Department department) {
            this.department = department;
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
                                                       LocalDateTime now) {
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

        int tasksWithoutDepartment = 0;
        for (Task task : activeTasks) {
            if (task.getDepartment() == null) {
                tasksWithoutDepartment++;
                continue;
            }
            Stats stats = byCode.get(task.getDepartment().getCode());
            if (stats != null && !assignedTasks.contains(task.getId())) stats.waiting.add(task);
        }

        Comparator<Staff> byName = Comparator.comparing((Staff s) -> String.valueOf(s.getName()).toLowerCase()).thenComparing(s -> String.valueOf(s.getId()));
        Comparator<Task> mostImportantFirst = MOST_IMPORTANT_FIRST;
        for (Stats stats : all) {
            stats.freeStaff.sort(byName);
            stats.lendable.sort(byName);
            stats.waiting.sort(mostImportantFirst);
        }

        List<Stats> pressured = new ArrayList<>();
        for (Stats stats : all) {
            if (stats.uncovered() > 0) pressured.add(stats);
        }
        pressured.sort(Comparator
                .comparing((Stats s) -> severity(s, today))
                .thenComparingInt((Stats s) -> -s.uncovered())
                .thenComparing(s -> s.department.getName().toLowerCase()));

        List<DepartmentRecommendation> recommendations = new ArrayList<>();
        List<UnsupportedDepartment> unsupported = new ArrayList<>();

        for (Stats receiver : pressured) {
            Severity severity = severity(receiver, today);
            Pressure pressure = pressure(receiver, today);
            List<Task> uncovered = receiver.uncoveredTasks();
            int wanted = Math.min(uncovered.size(), MAX_SUGGESTIONS_PER_DEPARTMENT);
            List<Suggestion> suggestions = new ArrayList<>();
            int uncoveredBefore = uncovered.size();
            int minutesBefore = minutes(uncovered);

            while (suggestions.size() < wanted) {
                Stats donor = bestDonor(all, receiver);
                if (donor == null) break;
                Staff chosen = donor.lendable.get(0);
                Task covers = uncovered.get(suggestions.size());
                int index = suggestions.size();
                int minutesAfter = minutes(uncovered.subList(index + 1, uncovered.size()));
                int freeBefore = donor.freeStaff.size();
                int availableBefore = donor.availableStaff.size();
                DonorImpact donorImpact = new DonorImpact(freeBefore, freeBefore - 1, availableBefore, availableBefore - 1, donor.waiting.size());
                ReceiverImpact receiverImpact = new ReceiverImpact(
                        uncoveredBefore - index, uncoveredBefore - index - 1,
                        minutes(uncovered.subList(index, uncovered.size())), minutesAfter);
                suggestions.add(new Suggestion(chosen.getId(), chosen.getName(), donor.department.getCode(), donor.department.getName(),
                        covers.getTitle(), donorImpact, receiverImpact, suggestionReasons(donor, freeBefore, covers)));
                // The chosen person leaves the lending department's lists, so later receivers see what is really left
                donor.lendable.remove(chosen);
                donor.freeStaff.remove(chosen);
                donor.availableStaff.remove(chosen);
            }

            if (suggestions.isEmpty()) {
                unsupported.add(new UnsupportedDepartment(receiver.department.getCode(), receiver.department.getName(), severity, pressure,
                        "No suitable support is available: every other department either has no free STAFF member who can sign in "
                                + "to lend, or would be left without a free, available staff member or without enough free staff for its "
                                + "own waiting work if it lent someone."));
            } else {
                recommendations.add(new DepartmentRecommendation(receiver.department.getCode(), receiver.department.getName(),
                        severity, pressure, receiverReasons(receiver, pressure), suggestions));
            }
        }

        return new WorkloadRecommendationsDto(now, recommendations, unsupported,
                limitations(tasksWithoutDepartment));
    }

    /**
     * The department that can best lend one more person: after lending it must still have at least one free, available
     * staff member and enough free staff for every task waiting in it. A person already suggested to another department
     * has been removed from its lists.
     */
    private static Stats bestDonor(List<Stats> all, Stats receiver) {
        Stats best = null;
        int bestSpare = Integer.MIN_VALUE;
        for (Stats candidate : all) {
            if (candidate == receiver) continue;
            int free = candidate.freeStaff.size();
            int freeAfter = free - 1;
            // Reserve rule: at least one free person must remain, whether or not anything is waiting
            if (candidate.lendable.isEmpty() || freeAfter < Math.max(1, candidate.waiting.size())) continue;
            int spare = freeAfter - candidate.waiting.size();
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

    private static Pressure pressure(Stats stats, LocalDate today) {
        int urgentOrHigh = 0;
        int dueSoon = 0;
        int noDuration = 0;
        for (Task task : stats.waiting) {
            if (task.getPriority() == Priority.URGENT || task.getPriority() == Priority.HIGH) urgentOrHigh++;
            if (task.getDueDate() != null && !task.getDueDate().isAfter(today.plusDays(DUE_SOON_DAYS))) dueSoon++;
            if (task.getEstimatedDuration() == null) noDuration++;
        }
        return new Pressure(stats.waiting.size(), minutes(stats.waiting), urgentOrHigh, dueSoon,
                stats.activeStaff.size(), stats.availableStaff.size(), stats.freeStaff.size(),
                stats.uncovered(), minutes(stats.uncoveredTasks()), noDuration);
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

    private static List<String> receiverReasons(Stats stats, Pressure p) {
        List<String> reasons = new ArrayList<>();
        reasons.add(plural(p.waitingTasks(), "task") + " waiting for someone (" + p.waitingMinutes() + " min) and "
                + p.freeStaff() + " available staff with no active task, so " + plural(p.uncoveredTasks(), "task")
                + " (" + p.uncoveredMinutes() + " min) cannot be picked up by the department's own staff.");
        if (p.urgentOrHighTasks() > 0) reasons.add(plural(p.urgentOrHighTasks(), "waiting task") + " with High or Urgent priority.");
        if (p.overdueOrDueSoonTasks() > 0) reasons.add(plural(p.overdueOrDueSoonTasks(), "waiting task") + " overdue or due within a day.");
        if (p.tasksWithoutDuration() > 0) {
            reasons.add(plural(p.tasksWithoutDuration(), "waiting task") + " with no estimated duration, counted as 0 minutes.");
        }
        return reasons;
    }

    private static List<String> suggestionReasons(Stats donor, int freeBefore, Task covers) {
        List<String> reasons = new ArrayList<>();
        reasons.add(donor.department.getName() + " has " + freeBefore + " available staff with no active task and "
                + plural(donor.waiting.size(), "waiting task") + "; lending one leaves " + (freeBefore - 1)
                + " free, so it keeps a free person in reserve and still covers its own waiting work.");
        String detail = (covers.getPriority() == null ? "" : covers.getPriority() + " priority, ")
                + (covers.getEstimatedDuration() == null ? "no estimated duration" : covers.getEstimatedDuration() + " min")
                + (covers.getDueDate() == null ? "" : ", due " + covers.getDueDate());
        reasons.add("Could take \"" + covers.getTitle() + "\" (" + detail + ") if support were arranged.");
        return reasons;
    }

    private static List<String> limitations(int tasksWithoutDepartment) {
        List<String> limitations = new ArrayList<>();
        limitations.add("Skills are not recorded in Taskotel, so a person's suitability for the receiving department's work is not assessed. A manager must judge it.");
        limitations.add("Only active STAFF accounts that can sign in (email verified, credentials set up) are suggested. Managers, admins and staff still waiting for email verification or credentials setup are never suggested, although they still count as free staff of their own department.");
        limitations.add("Shifts and working hours are not recorded. \"Available\" means the person is active, marked Available and has no active task right now.");
        limitations.add("Taskotel currently lets staff take tasks only from their own department, so these suggestions cannot be put into effect yet. Nothing here moves staff or tasks.");
        if (tasksWithoutDepartment > 0) {
            limitations.add(plural(tasksWithoutDepartment, "active task") + " with no department " + (tasksWithoutDepartment == 1 ? "is" : "are") + " not counted.");
        }
        return limitations;
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
