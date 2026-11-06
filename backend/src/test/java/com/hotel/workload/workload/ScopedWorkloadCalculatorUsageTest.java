package com.hotel.workload.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ScopedWorkloadCalculator} trusts the scope it is given, so it must stay an internal seam: only the dashboard may use it and only
 * {@link WorkloadServiceImpl} may implement it. It must not appear on the public {@link WorkloadService}, and nothing may reach it from a request.
 */
public class ScopedWorkloadCalculatorUsageTest {

    private static final Path MAIN = Paths.get("src", "main", "java");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> result = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
            return result;
        }
    }

    @Test
    @DisplayName("Only DashboardServiceImpl references ScopedWorkloadCalculator, and only WorkloadServiceImpl implements it")
    public void usageIsRestricted() throws IOException {
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("ScopedWorkloadCalculator.java")) continue;
            String text = Files.readString(source);
            if (!text.contains("ScopedWorkloadCalculator")) continue;
            assertTrue(name.equals("DashboardServiceImpl.java") || name.equals("WorkloadServiceImpl.java"), name + " must not use ScopedWorkloadCalculator");
            if (name.equals("WorkloadServiceImpl.java")) {
                assertTrue(text.contains("implements WorkloadService, ScopedWorkloadCalculator"));
            }
        }
    }

    @Test
    @DisplayName("The public WorkloadService and the controller never accept or expose a scope")
    public void publicApiHasNoScope() throws IOException {
        String service = Files.readString(MAIN.resolve(Paths.get("com", "hotel", "workload", "workload", "WorkloadService.java")));
        String controller = Files.readString(MAIN.resolve(Paths.get("com", "hotel", "workload", "workload", "WorkloadController.java")));
        assertFalse(service.contains("DepartmentScope"));
        assertFalse(controller.contains("DepartmentScope"));
        assertFalse(controller.contains("ScopedWorkloadCalculator"));
    }

    @Test
    @DisplayName("The deleted DashboardWorkloadSource is gone from the sources")
    public void formerDashboardSourceIsGone() throws IOException {
        for (Path source : mainSources()) {
            assertFalse(Files.readString(source).contains("DashboardWorkloadSource"), source.toString());
        }
    }
}
