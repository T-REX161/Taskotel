package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.DonorStaff;
import com.hotel.workload.support.SupportDtos.SupportAssignmentDto;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cross-department support shows the people involved with their existing profile picture: the donor staff offered in the plan builder
 * (DonorStaff.profileImageUrl, also in a Manager's redacted view) and every support record (SupportAssignmentDto.staffProfileImageUrl) in the
 * confirmation, the history, and the end and archive answers. Nothing else in those answers changes.
 */
@SpringBootTest
@Transactional
public class SupportStaffPictureTest {

    private static final String PICTURE = "https://res.cloudinary.com/example/image/upload/v1/taskotel/profile-pictures/spic.jpg";

    @Autowired private SupportAssignmentService service;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private SupportTestFixture fx;
    private SupportTestFixture.Scenario s;
    private Staff pictured, plain;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        s = fx.overloaded(2, 3);
        pictured = s.donors.get(0);
        plain = s.donors.get(1);
        User account = pictured.getUser();
        account.setProfileImageUrl(PICTURE);
        users.saveAndFlush(account);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("spic_manager", s.receiving);
        SupportTestFixture.signInAs("spic_admin", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
    }

    private static DonorStaff offered(SupportDtos.Options options, Staff member) {
        return options.donors().stream().flatMap(d -> d.staff().stream()).filter(p -> p.staffId().equals(member.getId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Plan builder options: each offered person carries their picture, for an Admin and in a Manager's redacted view")
    void optionsCarryPictures() {
        SupportDtos.Options admin = service.getOptions(s.receiving.getCode());
        assertEquals(PICTURE, offered(admin, pictured).profileImageUrl());
        assertNull(offered(admin, plain).profileImageUrl());

        SupportTestFixture.signInAs("spic_manager", "MANAGER");
        SupportDtos.Options manager = service.getOptions(s.receiving.getCode());
        assertEquals(PICTURE, offered(manager, pictured).profileImageUrl());
        assertNull(offered(manager, pictured).activeTaskCount(), "the Manager's view is still redacted: no figures");
    }

    @Test
    @DisplayName("Support records carry the person's picture: confirmation, Admin and Manager history, end")
    void recordsCarryPictures() {
        List<SupportAssignmentDto> created = service.confirm(fx.plan(s.receiving, pictured, s.tasks.get(0)));
        SupportAssignmentDto mine = created.stream().filter(r -> r.staffId().equals(pictured.getId())).findFirst().orElseThrow();
        assertEquals(PICTURE, mine.staffProfileImageUrl());

        assertEquals(PICTURE, rowFor(service.list(null), pictured.getId()).staffProfileImageUrl());

        SupportTestFixture.signInAs("spic_manager", "MANAGER");
        SupportAssignmentDto managerRow = rowFor(service.list(null), pictured.getId());
        assertEquals(PICTURE, managerRow.staffProfileImageUrl());
        assertNull(managerRow.approvedBy(), "the Manager's projection is unchanged: no approver");

        SupportTestFixture.signInAs("spic_admin", "ADMIN");
        SupportAssignmentDto ended = service.end(mine.id());
        assertEquals(PICTURE, ended.staffProfileImageUrl());
    }

    @Test
    @DisplayName("A record for someone without a picture carries null, and nothing else in the row changes")
    void recordWithoutPicture() {
        SupportAssignmentDto row = service.confirm(fx.plan(s.receiving, plain, s.tasks.get(0))).get(0);
        assertNull(row.staffProfileImageUrl());
        assertEquals(plain.getName(), row.staffName());
        assertEquals(row, row.withStaffProfileImageUrl(null), "adding the picture changes no other field");
    }

    private static SupportAssignmentDto rowFor(List<SupportAssignmentDto> rows, UUID staffId) {
        return rows.stream().filter(r -> staffId.equals(r.staffId())).findFirst().orElseThrow();
    }
}
