package com.hotel.workload.support;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Temporary cross-department support. Every route is restricted to ADMIN and MANAGER in SecurityConfig
 * (for all HTTP methods), and the service revalidates everything on confirm.
 */
@RestController
@RequestMapping("/api/support")
public class SupportController {

    private final SupportAssignmentService supportService;

    public SupportController(SupportAssignmentService supportService) {
        this.supportService = supportService;
    }

    /** The waiting tasks and the staff who could help, for building a plan for one overloaded department. */
    @GetMapping("/options")
    public ResponseEntity<SupportDtos.Options> options(@RequestParam("receivingDepartment") String receivingDepartment) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(supportService.getOptions(receivingDepartment));
    }

    /** Validates a plan and shows its workload impact on both departments. Changes nothing. */
    @PostMapping("/preview")
    public ResponseEntity<SupportDtos.Preview> preview(@Valid @RequestBody SupportPlanRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(supportService.preview(request));
    }

    /** Revalidates and confirms the plan, creating the temporary assignments. */
    @PostMapping("/assignments")
    public ResponseEntity<List<SupportDtos.SupportAssignmentDto>> confirm(@Valid @RequestBody SupportPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supportService.confirm(request));
    }

    /** Support history (active, completed or cancelled), newest first. */
    @GetMapping("/assignments")
    public ResponseEntity<List<SupportDtos.SupportAssignmentDto>> list(@RequestParam(value = "status", required = false) SupportStatus status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(supportService.list(status));
    }

    /** Removes one ended record from the history list (archived, not deleted). Active support is refused. */
    @PostMapping("/assignments/{id}/archive")
    public ResponseEntity<SupportDtos.SupportAssignmentDto> archive(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(supportService.archive(id));
    }

    /** Ends one active support by hand. The record stays in the history. */
    @PostMapping("/assignments/{id}/end")
    public ResponseEntity<SupportDtos.SupportAssignmentDto> end(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(supportService.end(id));
    }
}
