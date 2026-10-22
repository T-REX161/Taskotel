package com.hotel.workload.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
public class DashboardControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    private User adminUser;
    private User managerUser;
    private User staffUser;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        assignmentRepository.deleteAll();
        taskRepository.deleteAll();
        staffRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = new User();
        adminUser.setUsername("admin1");
        adminUser.setEmail("admin1@hotel.com");
        adminUser.setPassword("password");
        adminUser.setRole(Role.ADMIN);
        userRepository.save(adminUser);

        managerUser = new User();
        managerUser.setUsername("manager1");
        managerUser.setEmail("manager1@hotel.com");
        managerUser.setPassword("password");
        managerUser.setRole(Role.MANAGER);
        userRepository.save(managerUser);

        staffUser = new User();
        staffUser.setUsername("staff1");
        staffUser.setEmail("staff1@hotel.com");
        staffUser.setPassword("password");
        staffUser.setRole(Role.STAFF);
        userRepository.save(staffUser);

        Staff staff = new Staff();
        staff.setName("John Doe");
        staff.setEmail("john@example.com");
        staff.setDepartment(Department.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        staff.setUser(staffUser);
        staffRepository.save(staff);

        User staffUser2 = new User();
        staffUser2.setUsername("staff2");
        staffUser2.setEmail("staff2@hotel.com");
        staffUser2.setPassword("password");
        staffUser2.setRole(Role.STAFF);
        userRepository.save(staffUser2);

        Staff staff2 = new Staff();
        staff2.setName("Jane Smith");
        staff2.setEmail("jane@example.com");
        staff2.setDepartment(Department.MAINTENANCE);
        staff2.setAvailability(Availability.AVAILABLE);
        staff2.setActive(true);
        staff2.setUser(staffUser2);
        staffRepository.save(staff2);

        Task task1 = new Task();
        task1.setTitle("Task 1");
        task1.setPriority(Priority.HIGH);
        task1.setStatus(TaskStatus.IN_PROGRESS);
        task1.setEstimatedDuration(120);
        task1.setCreatedBy(adminUser);
        taskRepository.save(task1);

        Task task2 = new Task();
        task2.setTitle("Task 2");
        task2.setPriority(Priority.MEDIUM);
        task2.setStatus(TaskStatus.ASSIGNED);
        task2.setEstimatedDuration(60);
        task2.setCreatedBy(adminUser);
        taskRepository.save(task2);

        Assignment a1 = new Assignment();
        a1.setStaff(staff);
        a1.setTask(task1);
        a1.setAssignedAt(LocalDateTime.now());
        assignmentRepository.save(a1);

        Assignment a2 = new Assignment();
        a2.setStaff(staff2);
        a2.setTask(task2);
        a2.setAssignedAt(LocalDateTime.now());
        assignmentRepository.save(a2);
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    public void testAdminDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStaff").value(2))
                .andExpect(jsonPath("$.availableStaff").value(2))
                .andExpect(jsonPath("$.totalTasks").value(2))
                .andExpect(jsonPath("$.totalActiveAssignments").value(2))
                .andExpect(jsonPath("$.totalStaffWithWorkload").value(2));
    }

    @Test
    @WithMockUser(username = "manager1", roles = {"MANAGER"})
    public void testManagerDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStaff").value(2));
    }

    @Test
    @WithMockUser(username = "staff1", roles = {"STAFF"})
    public void testStaffDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workload.staffName").value("John Doe"))
                .andExpect(jsonPath("$.workload.totalEstimatedDuration").value(120))
                .andExpect(jsonPath("$.inProgressTasks").value(1))
                .andExpect(jsonPath("$.totalStaff").doesNotExist()) // Should not expose admin fields
                .andExpect(jsonPath("$.recentAssignments.length()").value(1));
    }

    @Test
    public void testUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized());
    }
}
