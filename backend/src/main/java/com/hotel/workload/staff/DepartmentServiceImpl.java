package com.hotel.workload.staff;

import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DepartmentServiceImpl implements DepartmentService {

    private static final List<TaskStatus> ACTIVE_STATUSES =
            Arrays.asList(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final TaskRepository taskRepository;

    public DepartmentServiceImpl(DepartmentRepository departmentRepository,
                                 StaffRepository staffRepository,
                                 TaskRepository taskRepository) {
        this.departmentRepository = departmentRepository;
        this.staffRepository = staffRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentDto> getAllDepartments() {
        return departmentRepository.findAllByOrderByCreatedAtAscNameAsc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public DepartmentDto createDepartment(CreateDepartmentRequest request) {
        String name = normalizeName(request.getName());
        if (departmentRepository.existsByNameIgnoreCase(name)) {
            throw duplicateName(name);
        }

        Department department = new Department();
        department.setName(name);
        department.setCode(uniqueCode(name));
        department.setDescription(normalizeDescription(request.getDescription()));
        department.setIconKey(validIcon(request.getIconKey()));
        department.setColorKey(validColor(request.getColorKey()));
        department.setActive(request.getActive() == null || request.getActive());
        department.setHeadStaff(resolveHead(request.getHeadStaffId(), null));

        try {
            return toDto(departmentRepository.saveAndFlush(department));
        } catch (DataIntegrityViolationException e) {
            // Two requests creating the same name at once: the unique index rejected the second one
            throw duplicateName(name);
        }
    }

    @Override
    @Transactional
    public DepartmentDto updateDepartment(String code, UpdateDepartmentRequest request) {
        Department department = departmentRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));

        String name = normalizeName(request.getName());
        if (departmentRepository.existsByNameIgnoreCaseAndIdNot(name, department.getId())) {
            throw duplicateName(name);
        }

        department.setName(name); // the code is kept: staff and tasks keep pointing at it
        department.setDescription(normalizeDescription(request.getDescription()));
        department.setIconKey(validIcon(request.getIconKey()));
        department.setColorKey(validColor(request.getColorKey()));
        department.setActive(request.getActive());
        department.setHeadStaff(resolveHead(request.getHeadStaffId(), department.getHeadStaff()));

        try {
            return toDto(departmentRepository.saveAndFlush(department));
        } catch (DataIntegrityViolationException e) {
            throw duplicateName(name);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DepartmentDto toDto(Department department) {
        long staffCount = staffRepository.countByDepartmentAndActiveTrue(department);
        long activeTasks = taskRepository.countByDepartmentAndStatusIn(department, ACTIVE_STATUSES);

        String status;
        if (staffCount == 0) {
            // Rule: cannot calculate a workload ratio when the staff count is zero.
            status = "N/A (No Staff)";
        } else {
            double ratio = (double) activeTasks / staffCount;
            if (ratio < 2.0) {
                status = "Optimal";
            } else if (ratio < 4.0) {
                status = "Moderate";
            } else {
                status = "High";
            }
        }

        Staff head = department.getHeadStaff();

        DepartmentDto dto = new DepartmentDto();
        dto.setId(department.getId());
        dto.setCode(department.getCode());
        dto.setName(department.getName());
        dto.setDescription(department.getDescription());
        dto.setIconKey(department.getIconKey());
        dto.setColorKey(department.getColorKey());
        dto.setActive(department.isActive());
        dto.setHeadStaffId(head != null ? head.getId() : null);
        dto.setHeadStaffName(head != null ? head.getName() : null);
        dto.setStaffCount(staffCount);
        dto.setActiveTasks(activeTasks);
        dto.setStatus(status);
        dto.setSupervisor(head != null ? head.getName() : "Not Assigned");
        return dto;
    }

    private static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department name must be between 2 and 100 characters");
        }
        return name;
    }

    private static String normalizeDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String description = raw.trim();
        if (description.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description must be at most 500 characters");
        }
        return description;
    }

    private static String validIcon(String key) {
        if (key == null || key.isBlank()) {
            return DepartmentCatalog.DEFAULT_ICON_KEY;
        }
        if (!DepartmentCatalog.ICON_KEYS.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported icon: " + key);
        }
        return key;
    }

    private static String validColor(String key) {
        if (key == null || key.isBlank()) {
            return DepartmentCatalog.DEFAULT_COLOR_KEY;
        }
        if (!DepartmentCatalog.COLOR_KEYS.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported color: " + key);
        }
        return key;
    }

    /** The head must be an existing active staff member. An unchanged current head is always accepted. */
    private Staff resolveHead(UUID headStaffId, Staff current) {
        if (headStaffId == null) {
            return null;
        }
        if (current != null && headStaffId.equals(current.getId())) {
            return current;
        }
        Staff staff = staffRepository.findById(headStaffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department head not found"));
        if (!staff.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The department head must be an active staff member");
        }
        return staff;
    }

    private String uniqueCode(String name) {
        String base = DepartmentCatalog.codeFromName(name);
        for (int attempt = 1; attempt <= 99; attempt++) {
            String candidate = DepartmentCatalog.withSuffix(base, attempt);
            if (!departmentRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Could not generate a unique code for this department name");
    }

    private static ResponseStatusException duplicateName(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A department named '" + name + "' already exists");
    }
}
