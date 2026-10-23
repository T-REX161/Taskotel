package com.hotel.workload.staff;

import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class DepartmentServiceImplTest {

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private StaffRepository staffRepository;

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private DepartmentServiceImpl departmentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetAllDepartments() {
        List<TaskStatus> activeStatuses = Arrays.asList(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

        // HOUSEKEEPING: 10 staff, 15 tasks -> 1.5 ratio -> Optimal
        when(staffRepository.countByDepartmentAndActiveTrue(TestDepartments.HOUSEKEEPING)).thenReturn(10L);
        when(taskRepository.countByDepartmentAndStatusIn(TestDepartments.HOUSEKEEPING, activeStatuses)).thenReturn(15L);

        // FRONT_DESK: 0 staff, 5 tasks -> N/A (No Staff)
        when(staffRepository.countByDepartmentAndActiveTrue(TestDepartments.FRONT_DESK)).thenReturn(0L);
        when(taskRepository.countByDepartmentAndStatusIn(TestDepartments.FRONT_DESK, activeStatuses)).thenReturn(5L);

        // MAINTENANCE: 2 staff, 10 tasks -> 5.0 ratio -> High
        when(staffRepository.countByDepartmentAndActiveTrue(TestDepartments.MAINTENANCE)).thenReturn(2L);
        when(taskRepository.countByDepartmentAndStatusIn(TestDepartments.MAINTENANCE, activeStatuses)).thenReturn(10L);

        // ROOM_SERVICE: 5 staff, 15 tasks -> 3.0 ratio -> Moderate
        when(staffRepository.countByDepartmentAndActiveTrue(TestDepartments.ROOM_SERVICE)).thenReturn(5L);
        when(taskRepository.countByDepartmentAndStatusIn(TestDepartments.ROOM_SERVICE, activeStatuses)).thenReturn(15L);

        // FOOD_AND_BEVERAGE: 0 staff, 0 tasks -> N/A (No Staff)
        when(staffRepository.countByDepartmentAndActiveTrue(TestDepartments.FOOD_AND_BEVERAGE)).thenReturn(0L);
        when(taskRepository.countByDepartmentAndStatusIn(TestDepartments.FOOD_AND_BEVERAGE, activeStatuses)).thenReturn(0L);
        
        // SECURITY: 10 staff, 0 tasks -> 0 ratio -> Optimal
        when(staffRepository.countByDepartmentAndActiveTrue(TestDepartments.SECURITY)).thenReturn(10L);
        when(taskRepository.countByDepartmentAndStatusIn(TestDepartments.SECURITY, activeStatuses)).thenReturn(0L);

        when(departmentRepository.findAllByOrderByCreatedAtAscNameAsc()).thenReturn(TestDepartments.ALL);

        List<DepartmentDto> result = departmentService.getAllDepartments();

        assertEquals(6, result.size());

        DepartmentDto housekeeping = result.stream().filter(d -> d.getCode().equals("HOUSEKEEPING")).findFirst().get();
        assertEquals("Housekeeping", housekeeping.getName());
        assertEquals(10L, housekeeping.getStaffCount());
        assertEquals(15L, housekeeping.getActiveTasks());
        assertEquals("Optimal", housekeeping.getStatus());
        assertEquals("Not Assigned", housekeeping.getSupervisor());

        DepartmentDto frontDesk = result.stream().filter(d -> d.getCode().equals("FRONT_DESK")).findFirst().get();
        assertEquals("Front Desk", frontDesk.getName());
        assertEquals(0L, frontDesk.getStaffCount());
        assertEquals(5L, frontDesk.getActiveTasks());
        assertEquals("N/A (No Staff)", frontDesk.getStatus());

        DepartmentDto maintenance = result.stream().filter(d -> d.getCode().equals("MAINTENANCE")).findFirst().get();
        assertEquals("Maintenance", maintenance.getName());
        assertEquals(2L, maintenance.getStaffCount());
        assertEquals(10L, maintenance.getActiveTasks());
        assertEquals("High", maintenance.getStatus());
        
        DepartmentDto fnb = result.stream().filter(d -> d.getCode().equals("FOOD_AND_BEVERAGE")).findFirst().get();
        assertEquals("Food & Beverage", fnb.getName());
        assertEquals(0L, fnb.getStaffCount());
        assertEquals(0L, fnb.getActiveTasks());
        assertEquals("N/A (No Staff)", fnb.getStatus());
    }
}
