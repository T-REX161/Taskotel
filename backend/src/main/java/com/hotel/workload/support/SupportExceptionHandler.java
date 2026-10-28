package com.hotel.workload.support;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Error bodies for the support endpoints: a message plus every problem found. */
@RestControllerAdvice(assignableTypes = SupportController.class)
public class SupportExceptionHandler {

    @ExceptionHandler(SupportPlanException.class)
    public ResponseEntity<Map<String, Object>> handlePlan(SupportPlanException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.getStatus().value());
        body.put("message", ex.getMessage());
        body.put("problems", ex.getProblems());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /** Two requests changed the same record at once (version check failed): the loser is told to refresh. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrentChange(ObjectOptimisticLockingFailureException ex) {
        return error(409, "CONCURRENT_CHANGE", "Another change was made at the same time.",
                "This support was changed by someone else at the same moment. Refresh to see the latest.");
    }

    /**
     * The database refused the write. That is a server-side problem, never a concurrent change, so it is reported as such.
     * The most likely cause is a database that has not had migration V6 applied (its end-reason check does not know
     * MANUALLY_ENDED yet). The transaction was rolled back, so nothing was changed.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase();
        if (detail.contains("end_reason")) {
            return error(500, "SCHEMA_OUT_OF_DATE", "The database does not accept this end reason yet.",
                    "The database has not been upgraded for ending support (migration V6). Nothing was changed. Ask an administrator to apply it.");
        }
        return error(500, "DATA_INTEGRITY", "The database refused the change.", "The database refused this change. Nothing was changed.");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String problem, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        body.put("problems", List.of(new SupportDtos.Problem(code, problem, null, null)));
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<SupportDtos.Problem> problems = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new SupportDtos.Problem("INVALID_REQUEST", e.getDefaultMessage(), null, null)).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("message", problems.isEmpty() ? "Invalid request" : problems.get(0).message());
        body.put("problems", problems);
        return ResponseEntity.badRequest().body(body);
    }
}
