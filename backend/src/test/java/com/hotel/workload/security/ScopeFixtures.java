package com.hotel.workload.security;

import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Realistic database fixtures for department-scope tests: departments, Managers (account + active Staff record in a department),
 * ordinary Staff, and Admins. Plain class (not a Spring bean): a test builds it from its own autowired repositories.
 * Every method is idempotent by user name or department code, so a test may call it from {@code @BeforeEach} repeatedly.
 */
public class ScopeFixtures {

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;

    public ScopeFixtures(UserRepository userRepository, StaffRepository staffRepository, DepartmentRepository departmentRepository,
                         PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** The department with this code, created (active) if it does not exist yet. */
    public Department department(String code) {
        return departmentRepository.findByCode(code).orElseGet(() -> {
            Department department = new Department(code, code + " name");
            department.setCreatedAt(LocalDateTime.now());
            return departmentRepository.saveAndFlush(department);
        });
    }

    /** An account with the given role and no Staff record (the Admin shape). Existing accounts are returned unchanged. */
    public User account(String username, Role role, boolean active) {
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            return existing.get();
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@scope.test");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(role);
        user.setActive(active);
        return userRepository.saveAndFlush(user);
    }

    /** An account with the role AND an active Staff record in the department. Existing Staff records are returned unchanged. */
    public Staff staffRecord(String username, Role role, Department department) {
        Optional<Staff> existing = staffRepository.findByUserUsername(username);
        if (existing.isPresent()) {
            return existing.get();
        }
        User user = account(username, role, true);
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Scope " + username);
        staff.setEmail(username + "@scope.test");
        staff.setPhone("555-0100");
        staff.setDepartment(department);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        return staffRepository.saveAndFlush(staff);
    }

    /** A valid Manager: active account, active Staff record, in the department. */
    public Staff manager(String username, Department department) {
        return staffRecord(username, Role.MANAGER, department);
    }

    /** An ordinary STAFF-role member of the department. */
    public Staff staff(String username, Department department) {
        return staffRecord(username, Role.STAFF, department);
    }

    /** Removes the Staff record (if any) and the account with each user name; names that do not exist are ignored. */
    public void remove(String... usernames) {
        for (String username : usernames) {
            staffRepository.findByUserUsername(username).ifPresent(staffRepository::delete);
            staffRepository.flush();
            userRepository.findByUsername(username).ifPresent(userRepository::delete);
            userRepository.flush();
        }
    }
}
