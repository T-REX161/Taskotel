package com.hotel.workload.security;

import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;

    @Autowired
    public UserServiceImpl(UserRepository userRepository, StaffRepository staffRepository) {
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> getAllUsers() {
        List<User> users = userRepository.findAll();
        if (users.isEmpty()) {
            return Collections.emptyList();
        }

        List<Staff> staffList = staffRepository.findAll();
        Map<UUID, String> staffNameByUserId = staffList.stream()
                .filter(staff -> staff.getUser() != null && staff.getUser().getId() != null)
                .filter(staff -> staff.getName() != null && !staff.getName().isBlank())
                .collect(Collectors.toMap(
                        staff -> staff.getUser().getId(),
                        Staff::getName,
                        (existing, duplicate) -> existing
                ));

        return users.stream()
                .map(user -> UserDto.fromEntity(user, staffNameByUserId.get(user.getId())))
                .collect(Collectors.toList());
    }
}
