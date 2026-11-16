package com.hotel.workload.notification;

import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.WorkloadStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.hotel.workload.workload.WorkloadStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** The pure rules of the workload alert: the existing thresholds, which changes alert, and what the two emails contain. */
public class WorkloadAlertRulesTest {

    @Test
    @DisplayName("The existing thresholds are used unchanged: LOW 0-120, NORMAL 121-240, HIGH 241-360, AT_RISK above 360 minutes")
    public void thresholds() {
        int[] minutes = {0, 120, 121, 240, 241, 360, 361, 5000};
        WorkloadStatus[] expected = {LOW, LOW, NORMAL, NORMAL, HIGH, HIGH, AT_RISK, AT_RISK};
        for (int i = 0; i < minutes.length; i++) assertEquals(expected[i], WorkloadDto.deriveStatus(minutes[i]), minutes[i] + " minutes");
    }

    @Test
    @DisplayName("Only a rise into HIGH or AT_RISK alerts: LOW/NORMAL to HIGH, LOW/NORMAL to AT_RISK, HIGH to AT_RISK")
    public void whichChangesAlert() {
        for (WorkloadStatus from : new WorkloadStatus[] {LOW, NORMAL}) {
            assertTrue(WorkloadAlertService.isRise(from, HIGH), from + " -> HIGH");
            assertTrue(WorkloadAlertService.isRise(from, AT_RISK), from + " -> AT_RISK");
        }
        assertTrue(WorkloadAlertService.isRise(HIGH, AT_RISK), "HIGH -> AT_RISK");
    }

    @Test
    @DisplayName("Everything else does not alert: staying put, LOW <-> NORMAL, and every recovery")
    public void whichChangesDoNotAlert() {
        for (WorkloadStatus level : WorkloadStatus.values()) assertFalse(WorkloadAlertService.isRise(level, level), "unchanged " + level);
        assertFalse(WorkloadAlertService.isRise(LOW, NORMAL));
        assertFalse(WorkloadAlertService.isRise(NORMAL, LOW));
        assertFalse(WorkloadAlertService.isRise(AT_RISK, HIGH), "de-escalation");
        for (WorkloadStatus from : new WorkloadStatus[] {HIGH, AT_RISK}) {
            assertFalse(WorkloadAlertService.isRise(from, LOW), from + " -> LOW");
            assertFalse(WorkloadAlertService.isRise(from, NORMAL), from + " -> NORMAL");
        }
    }

    @Test
    @DisplayName("The Staff email: level, total minutes, task count and a short explanation; branded; no task titles, no link")
    public void staffEmail() {
        EmailContent email = NotificationEmailTemplates.workloadAlertForStaff("AT_RISK", 410, 2);
        assertEquals("Taskotel - Your workload is now AT RISK", email.subject());
        for (String fragment : new String[] {"Your workload has increased", "increased to AT RISK", "Workload level: AT RISK",
                "Active workload: 410 minutes", "Active tasks: 2", "Taskotel Team", "Notification Preferences"}) {
            assertTrue(email.text().contains(fragment), fragment + " in:\n" + email.text());
        }
        assertTrue(email.html().contains("Taskotel") && email.html().contains("410 minutes"));
        assertFalse(email.text().toLowerCase().contains("http") || email.html().contains("href"));
    }

    @Test
    @DisplayName("The Manager email: the staff member, department, department level, and only the department's minutes and tasks")
    public void managerEmail() {
        EmailContent email = NotificationEmailTemplates.workloadAlertForManager("Mia <b>Rao</b>\r\nBcc: x@evil.test", "Housekeeping", "HIGH", 250, 1);
        assertEquals("Taskotel - Workload alert: Mia <b>Rao</b> Bcc: x@evil.test is now HIGH", email.subject());
        assertFalse(email.subject().contains("\n") || email.subject().contains("\r"), "a subject is never split");
        for (String fragment : new String[] {"Department: Housekeeping", "Workload level: HIGH", "Active workload (department tasks): 250 minutes",
                "Active tasks (department): 1"}) {
            assertTrue(email.text().contains(fragment), fragment + " in:\n" + email.text());
        }
        assertFalse(email.html().contains("<b>Rao</b>"), "the name is escaped in the HTML");
        assertTrue(email.html().contains("&lt;b&gt;Rao&lt;/b&gt;"));
        assertFalse(email.text().toLowerCase().contains("global") || email.text().toLowerCase().contains("total"), "no global figure");
    }
}
