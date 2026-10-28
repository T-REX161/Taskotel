package com.hotel.workload.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: on the real database, ending support failed because its end-reason CHECK constraint did not yet allow
 * MANUALLY_ENDED (migration V6 not applied). The database refusal was reported as "changed by someone else at the same
 * moment", which sent the investigation after a concurrency problem that did not exist. A refused write is a server
 * problem and must be reported as one; only a failed version check is a concurrent change.
 */
public class SupportEndErrorMappingTest {

    private SupportAssignmentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(SupportAssignmentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SupportController(service)).setControllerAdvice(new SupportExceptionHandler()).build();
    }

    private static DataIntegrityViolationException refusedByDatabase(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException("ERROR: new row for relation \"support_assignments\" violates check constraint \"" + constraint + "\""));
    }

    @Test
    @DisplayName("A database that rejects MANUALLY_ENDED (V6 missing) is a 500 SCHEMA_OUT_OF_DATE that names the migration, not a 409 concurrent change")
    void missingMigrationIsNotReportedAsAConcurrentChange() throws Exception {
        Mockito.when(service.end(any())).thenThrow(refusedByDatabase("support_assignments_end_reason_check"));

        String body = mvc.perform(post("/api/support/assignments/" + UUID.randomUUID() + "/end"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.problems[0].code").value("SCHEMA_OUT_OF_DATE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("migration V6")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nothing was changed")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("someone else"), "the misleading concurrency wording must not appear");
        assertFalse(body.contains("CONCURRENT_CHANGE"));
    }

    @Test
    @DisplayName("Any other refused write is a 500 DATA_INTEGRITY, never claimed to be a concurrent change")
    void otherIntegrityFailuresAreServerErrors() throws Exception {
        Mockito.when(service.end(any())).thenThrow(refusedByDatabase("some_other_constraint"));

        mvc.perform(post("/api/support/assignments/" + UUID.randomUUID() + "/end"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.problems[0].code").value("DATA_INTEGRITY"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("someone else"))));
    }

    @Test
    @DisplayName("A failed version check is still the genuine concurrent change: 409 CONCURRENT_CHANGE")
    void failedVersionCheckIsAConcurrentChange() throws Exception {
        Mockito.when(service.end(any())).thenThrow(new ObjectOptimisticLockingFailureException(SupportAssignment.class, UUID.randomUUID()));

        mvc.perform(post("/api/support/assignments/" + UUID.randomUUID() + "/end"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.problems[0].code").value("CONCURRENT_CHANGE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("someone else")));
    }
}
