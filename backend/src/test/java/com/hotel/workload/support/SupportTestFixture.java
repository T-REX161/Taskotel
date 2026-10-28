package com.hotel.workload.support;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds isolated test data: every scenario gets its own brand-new departments, so other tests' rows never interfere. */
class SupportTestFixture {
    final UserRepository users;
    final StaffRepository staff;
    final TaskRepository tasks;
    final AssignmentRepository assignments;
    final DepartmentRepository departments;
    final SupportAssignmentRepository support;
    final String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int counter = 0;
    final List<String> createdUsernames = new ArrayList<>();
    final List<String> createdDepartmentCodes = new ArrayList<>();

    SupportTestFixture(UserRepository users, StaffRepository staff, TaskRepository tasks, AssignmentRepository assignments,
                       DepartmentRepository departments, SupportAssignmentRepository support) {
        this.users = users; this.staff = staff; this.tasks = tasks; this.assignments = assignments;
        this.departments = departments; this.support = support;
    }

    Department department(String label) {
        Department department = new Department("SUP" + tag + label, "Dept " + label + " " + tag);
        department.setActive(true);
        department.setCreatedAt(LocalDateTime.now());
        createdDepartmentCodes.add(department.getCode());
        return departments.saveAndFlush(department);
    }

    /** A person with full control over every field that decides eligibility. */
    Staff person(String name, Department department, Role role, boolean emailVerified, boolean setupRequired,
                 boolean accountActive, Availability availability, boolean staffActive) {
        User user = new User();
        String username = ("sup" + tag + (counter++)).toLowerCase();
        createdUsernames.add(username);
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword("not-used");
        user.setRole(role);
        user.setActive(accountActive);
        user.setEmailVerified(emailVerified);
        user.setCredentialsSetupRequired(setupRequired);
        user = users.saveAndFlush(user);
        Staff member = new Staff();
        member.setUser(user);
        member.setName(name);
        member.setEmail(user.getEmail());
        member.setDepartment(department);
        member.setAvailability(availability);
        member.setActive(staffActive);
        return staff.saveAndFlush(member);
    }

    /** An eligible, free person: active STAFF account that can sign in, available. */
    Staff eligible(String name, Department department) {
        return person(name, department, Role.STAFF, true, false, true, Availability.AVAILABLE, true);
    }

    Task task(String title, Department department, Priority priority, int minutes, TaskStatus status) {
        User creator = users.findByUsername("testadmin").orElseGet(() -> {
            User admin = new User();
            admin.setUsername("testadmin"); admin.setEmail("testadmin@hotel.com"); admin.setPassword("x");
            admin.setRole(Role.ADMIN); admin.setActive(true);
            return users.saveAndFlush(admin);
        });
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(priority);
        task.setStatus(status);
        task.setEstimatedDuration(minutes);
        task.setDueDate(LocalDate.now().plusDays(1));
        task.setDepartment(department);
        task.setCreatedBy(creator);
        return tasks.saveAndFlush(task);
    }

    Task waiting(String title, Department department, Priority priority, int minutes) {
        return task(title, department, priority, minutes, TaskStatus.PENDING);
    }

    Assignment assign(Task task, Staff member) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(member);
        task.setStatus(TaskStatus.ASSIGNED);
        tasks.saveAndFlush(task);
        return assignments.saveAndFlush(assignment);
    }

    /** A receiving department with one busy employee and {@code waitingTasks} unassigned tasks: all of them uncovered. */
    Scenario overloaded(int waitingTasks, int donorFreeEligible) {
        Scenario s = new Scenario();
        s.receiving = department("R");
        s.donor = department("D");
        Staff busy = eligible("Busy Hana", s.receiving);
        assign(task("Already assigned", s.receiving, Priority.MEDIUM, 40, TaskStatus.PENDING), busy);
        for (int i = 1; i <= waitingTasks; i++) {
            s.tasks.add(waiting("Waiting " + i, s.receiving, i == 1 ? Priority.URGENT : Priority.MEDIUM, 30 + i * 10));
        }
        for (int i = 1; i <= donorFreeEligible; i++) s.donors.add(eligible("Donor " + i, s.donor));
        return s;
    }

    static class Scenario {
        Department receiving;
        Department donor;
        final List<Task> tasks = new ArrayList<>();
        final List<Staff> donors = new ArrayList<>();
    }

    SupportPlanRequest plan(Department receiving, Object... staffAndTasks) {
        SupportPlanRequest request = new SupportPlanRequest();
        request.setReceivingDepartment(receiving.getCode());
        List<SupportPlanRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < staffAndTasks.length; i += 2) {
            items.add(new SupportPlanRequest.Item(((Staff) staffAndTasks[i]).getId(), ((Task) staffAndTasks[i + 1]).getId()));
        }
        request.setItems(items);
        return request;
    }

    static void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    static void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** For tests that really commit: removes everything this fixture created. */
    void cleanUp() {
        for (SupportAssignment s : support.findAll()) {
            if (createdDepartmentCodes.contains(s.getReceivingDepartmentCode())) support.delete(s);
        }
        for (Task t : tasks.findAll()) {
            if (t.getDepartment() != null && createdDepartmentCodes.contains(t.getDepartment().getCode())) {
                assignments.deleteAll(assignments.findAllByTaskId(t.getId()));
                tasks.delete(t);
            }
        }
        for (Staff s : staff.findAllWithUser()) {
            if (createdUsernames.contains(s.getUser().getUsername())) staff.delete(s);
        }
        for (String username : createdUsernames) users.findByUsername(username).ifPresent(users::delete);
        for (String code : createdDepartmentCodes) departments.findByCode(code).ifPresent(departments::delete);
    }
}
