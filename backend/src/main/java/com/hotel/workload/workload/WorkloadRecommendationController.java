package com.hotel.workload.workload;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workload/recommendations")
public class WorkloadRecommendationController {

    private final WorkloadRecommendationService recommendationService;

    public WorkloadRecommendationController(WorkloadRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * GET /api/workload/recommendations
     * Read-only suggestions for cross-department support. ADMIN and MANAGER only (see SecurityConfig).
     */
    @GetMapping
    public ResponseEntity<WorkloadRecommendationsDto> getRecommendations() {
        return ResponseEntity.ok(recommendationService.getRecommendations());
    }
}
