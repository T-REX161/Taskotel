package com.hotel.workload.common;

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
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Inserts sample users, staff and tasks into an empty database. The sample accounts share a well-known password, so seeding is
 * opt-in: it only runs when {@value #ENABLED_PROPERTY} is true (default false, environment variable DEMO_DATA_ENABLED), never under the
 * prod or production profile, and never when users already exist.
 */
@Component
@Profile("!prod & !production")
public class DemoDataSeeder implements CommandLineRunner {

    public static final String ENABLED_PROPERTY = "app.demo-data.enabled";

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final DepartmentRepository departmentRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public DemoDataSeeder(UserRepository userRepository,
                          StaffRepository staffRepository,
                          TaskRepository taskRepository,
                          AssignmentRepository assignmentRepository,
                          PasswordEncoder passwordEncoder,
                          Environment environment,
                          DepartmentRepository departmentRepository) {
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.departmentRepository = departmentRepository;
    }

    public DemoDataSeeder(UserRepository userRepository,
                          StaffRepository staffRepository,
                          TaskRepository taskRepository,
                          AssignmentRepository assignmentRepository,
                          PasswordEncoder passwordEncoder,
                          DepartmentRepository departmentRepository) {
        this(userRepository, staffRepository, taskRepository, assignmentRepository, passwordEncoder, null, departmentRepository);
    }

    /** One of the built-in departments, created by DepartmentBootstrap before this seeder runs. */
    private Department department(String code) {
        return departmentRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("Built-in department missing: " + code));
    }

    private boolean isDemoSeedingEnabled() {
        return environment != null && Boolean.TRUE.equals(environment.getProperty(ENABLED_PROPERTY, Boolean.class, false));
    }

    private boolean isProductionEnvironment() {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void run(String... args) throws Exception {
        if (isProductionEnvironment()) {
            System.out.println("Production profile detected. Demo data seeding is disabled.");
            return;
        }

        if (!isDemoSeedingEnabled()) {
            System.out.println("Demo data seeding is disabled (set " + ENABLED_PROPERTY + "=true to enable it on a local or test database). No demo users were created.");
            return;
        }

        if (userRepository.count() > 0) {
            System.out.println("Demo data already exists. Skipping initialization.");
            return;
        }

        System.out.println("Inserting demo data into PostgreSQL...");

        String defaultPassword = passwordEncoder.encode("password");

        // Users. Every demo account is created already email-verified with its first password set (credentialsSetupRequired is false by
        // default), so all five can sign in at once without SendGrid; real accounts still go through verification and setup.
        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@hotel.com");
        admin.setPassword(defaultPassword);
        admin.setRole(Role.ADMIN);
        admin.setEmailVerified(true);
        admin = userRepository.save(admin);

        User manager = new User();
        manager.setUsername("manager");
        manager.setEmail("manager@hotel.com");
        manager.setPassword(defaultPassword);
        manager.setRole(Role.MANAGER);
        manager.setEmailVerified(true);
        manager = userRepository.save(manager);

        User rahulUser = new User();
        rahulUser.setUsername("rahul");
        rahulUser.setEmail("rahul@hotel.com");
        rahulUser.setPassword(defaultPassword);
        rahulUser.setRole(Role.STAFF);
        rahulUser.setEmailVerified(true);
        rahulUser = userRepository.save(rahulUser);

        User priyaUser = new User();
        priyaUser.setUsername("priya");
        priyaUser.setEmail("priya@hotel.com");
        priyaUser.setPassword(defaultPassword);
        priyaUser.setRole(Role.STAFF);
        priyaUser.setEmailVerified(true);
        priyaUser = userRepository.save(priyaUser);

        User amitUser = new User();
        amitUser.setUsername("amit");
        amitUser.setEmail("amit@hotel.com");
        amitUser.setPassword(defaultPassword);
        amitUser.setRole(Role.STAFF);
        amitUser.setEmailVerified(true);
        amitUser = userRepository.save(amitUser);

        // Staff. The Manager needs a linked, active Staff record with an active department: that record is where a Manager's department
        // comes from, and without it every Manager request is refused (403). Housekeeping is the Manager's department.
        Staff managerStaff = new Staff();
        managerStaff.setUser(manager);
        managerStaff.setName("Manager");
        managerStaff.setEmail(manager.getEmail());
        managerStaff.setDepartment(department("HOUSEKEEPING"));
        managerStaff.setAvailability(Availability.AVAILABLE);
        staffRepository.save(managerStaff);

        Staff rahul = new Staff();
        rahul.setUser(rahulUser);
        rahul.setName("Rahul");
        rahul.setEmail("rahul@hotel.com");
        rahul.setDepartment(department("HOUSEKEEPING"));
        rahul.setAvailability(Availability.AVAILABLE);
        rahul = staffRepository.save(rahul);

        Staff priya = new Staff();
        priya.setUser(priyaUser);
        priya.setName("Priya");
        priya.setEmail("priya@hotel.com");
        priya.setDepartment(department("FRONT_DESK"));
        priya.setAvailability(Availability.AVAILABLE);
        priya = staffRepository.save(priya);

        Staff amit = new Staff();
        amit.setUser(amitUser);
        amit.setName("Amit");
        amit.setEmail("amit@hotel.com");
        amit.setDepartment(department("MAINTENANCE"));
        amit.setAvailability(Availability.UNAVAILABLE);
        amit = staffRepository.save(amit);

        // Tasks
        Task task1 = new Task();
        task1.setTitle("Room Cleaning");
        task1.setDepartment(department("HOUSEKEEPING"));
        task1.setPriority(Priority.HIGH);
        task1.setStatus(TaskStatus.ASSIGNED);
        task1.setEstimatedDuration(30);
        task1.setCreatedBy(manager);
        task1 = taskRepository.save(task1);

        Task task2 = new Task();
        task2.setTitle("Front Desk Assistance");
        task2.setDepartment(department("FRONT_DESK"));
        task2.setPriority(Priority.MEDIUM);
        task2.setStatus(TaskStatus.IN_PROGRESS);
        task2.setEstimatedDuration(45);
        task2.setCreatedBy(manager);
        task2 = taskRepository.save(task2);

        Task task3 = new Task();
        task3.setTitle("AC Maintenance");
        task3.setDepartment(department("MAINTENANCE"));
        task3.setPriority(Priority.URGENT);
        task3.setStatus(TaskStatus.PENDING);
        task3.setEstimatedDuration(60);
        task3.setCreatedBy(manager);
        task3 = taskRepository.save(task3);

        Task task4 = new Task();
        task4.setTitle("Room Inspection");
        task4.setDepartment(department("HOUSEKEEPING"));
        task4.setPriority(Priority.LOW);
        task4.setStatus(TaskStatus.ASSIGNED);
        task4.setEstimatedDuration(30);
        task4.setCreatedBy(admin);
        task4 = taskRepository.save(task4);

        // Assignments
        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(rahul);
        assignmentRepository.save(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task4);
        a2.setStaff(rahul);
        assignmentRepository.save(a2);

        Assignment a3 = new Assignment();
        a3.setTask(task2);
        a3.setStaff(priya);
        assignmentRepository.save(a3);

        System.out.println("Demo data inserted successfully!");
    }
}
