package com.hotel.workload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class UUIDVerificationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void verifyUUIDs() {
        System.out.println("\n--- UUID VERIFICATION RESULT ---");
        
        List<Map<String, Object>> users = jdbcTemplate.queryForList("SELECT id, username FROM users LIMIT 2");
        System.out.println("Users:");
        for (Map<String, Object> u : users) {
            System.out.println("  User " + u.get("username") + " ID: " + u.get("id") + " (Class: " + u.get("id").getClass().getName() + ")");
        }

        List<Map<String, Object>> staff = jdbcTemplate.queryForList("SELECT id, name, user_id FROM staff LIMIT 2");
        System.out.println("Staff:");
        for (Map<String, Object> s : staff) {
            System.out.println("  Staff " + s.get("name") + " ID: " + s.get("id") + ", User ID: " + s.get("user_id"));
        }

        List<Map<String, Object>> tasks = jdbcTemplate.queryForList("SELECT id, title, created_by_id FROM tasks LIMIT 2");
        System.out.println("Tasks:");
        for (Map<String, Object> t : tasks) {
            System.out.println("  Task '" + t.get("title") + "' ID: " + t.get("id") + ", Created By ID: " + t.get("created_by_id"));
        }

        List<Map<String, Object>> assignments = jdbcTemplate.queryForList("SELECT id, task_id, staff_id FROM assignments LIMIT 2");
        System.out.println("Assignments:");
        for (Map<String, Object> a : assignments) {
            System.out.println("  Assignment ID: " + a.get("id") + ", Task ID: " + a.get("task_id") + ", Staff ID: " + a.get("staff_id"));
        }

        System.out.println("--------------------------------\n");
    }
}
