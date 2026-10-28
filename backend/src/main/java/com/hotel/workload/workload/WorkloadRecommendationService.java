package com.hotel.workload.workload;

public interface WorkloadRecommendationService {
    /**
     * Read-only suggestions for departments with more waiting work than free staff, and the staff other departments
     * could lend. Calculated from the database on every call; nothing is stored, assigned or moved.
     */
    WorkloadRecommendationsDto getRecommendations();
}
