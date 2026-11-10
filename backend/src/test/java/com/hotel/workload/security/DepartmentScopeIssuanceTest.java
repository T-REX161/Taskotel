package com.hotel.workload.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 2I (C4): a {@link DepartmentScope} is trusted wherever it is passed, so only the scope resolver may create one. The factories stay
 * public because tests build scopes, so this is enforced by a source scan: production code outside the {@code security} package must
 * never create a scope, and no controller may touch a scope or the resolver at all (a request value must never reach a scope).
 * The internal workload seam that takes a scope is guarded separately by {@code ScopedWorkloadCalculatorUsageTest}.
 */
public class DepartmentScopeIssuanceTest {

    private static final Path MAIN = Paths.get("src", "main", "java");
    private static final Pattern CREATES_SCOPE = Pattern.compile("DepartmentScope\\s*\\.\\s*(all|own|department)\\s*\\(|new\\s+DepartmentScope\\s*\\(");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> result = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
            return result;
        }
    }

    /** Code only: a comment that mentions the resolver is not a use of it. */
    private static String code(Path source) throws IOException {
        return Files.readString(source).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static boolean inSecurityPackage(Path source) {
        return source.toString().replace('\\', '/').contains("/com/hotel/workload/security/");
    }

    @Test
    @DisplayName("Only the security package creates a DepartmentScope; everything else receives one from the resolver")
    public void onlyTheResolverIssuesScopes() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            if (inSecurityPackage(source)) continue;
            if (CREATES_SCOPE.matcher(code(source)).find()) offenders.add(source.toString());
        }
        assertTrue(offenders.isEmpty(), "These classes create a DepartmentScope themselves: " + offenders);
    }

    @Test
    @DisplayName("Inside the security package only CurrentUserScopeResolver (and DepartmentScope itself) creates one")
    public void withinSecurityOnlyTheResolver() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (!inSecurityPackage(source) || name.equals("CurrentUserScopeResolver.java") || name.equals("DepartmentScope.java")) continue;
            if (CREATES_SCOPE.matcher(code(source)).find()) offenders.add(name);
        }
        assertTrue(offenders.isEmpty(), "Unexpected scope creation: " + offenders);
    }

    @Test
    @DisplayName("Controllers never accept, build or pass a scope or the resolver")
    public void controllersNeverTouchScopes() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            if (!source.getFileName().toString().endsWith("Controller.java")) continue;
            String text = code(source);
            if (text.contains("DepartmentScope") || text.contains("CurrentUserScopeResolver")) offenders.add(source.getFileName().toString());
        }
        assertTrue(offenders.isEmpty(), "Controllers referencing a scope: " + offenders);
    }
}
