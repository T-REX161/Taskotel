package com.hotel.workload.support;

import org.springframework.http.HttpStatus;

import java.util.List;

/** A plan that cannot be carried out, with every problem found (not just the first). */
public class SupportPlanException extends RuntimeException {
    private final HttpStatus status;
    private final List<SupportDtos.Problem> problems;

    public SupportPlanException(HttpStatus status, String message, List<SupportDtos.Problem> problems) {
        super(message);
        this.status = status;
        this.problems = problems;
    }

    public HttpStatus getStatus() { return status; }
    public List<SupportDtos.Problem> getProblems() { return problems; }
}
