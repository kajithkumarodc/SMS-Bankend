package com.smsapp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Flyway migration filenames.
 *
 * <p>Two branches that both add migrations numbered from the same version merge
 * without any git conflict -- the filenames differ, and each side is a pure
 * addition. Flyway then refuses to start ("Found more than one migration with
 * version 29"), which fails every integration test at context load and, worse,
 * stops the application from booting at all. This test turns that into one
 * obvious failure at {@code mvn test} instead.
 */
class MigrationVersionsTest {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static Map<Integer, List<String>> byVersion() throws IOException {
        Map<Integer, List<String>> versions = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            files.map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(name -> {
                        Matcher m = VERSIONED.matcher(name);
                        if (m.matches()) {
                            versions.computeIfAbsent(Integer.parseInt(m.group(1)), v -> new ArrayList<>()).add(name);
                        }
                    });
        }
        return versions;
    }

    @Test
    void noTwoMigrationsShareAVersion() throws IOException {
        List<String> clashes = byVersion().entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + ": " + String.join(", ", e.getValue()))
                .toList();

        // Renumber the side that has NOT been applied to any deployed database:
        // renumbering an already-applied migration needs a flyway_schema_history
        // repair on every environment that ran it.
        assertThat(clashes)
                .withFailMessage("Two migrations share a version, so Flyway cannot start: %s", clashes)
                .isEmpty();
    }

    @Test
    void versionsRunContiguouslyFromOne() throws IOException {
        List<Integer> found = byVersion().keySet().stream().sorted().toList();
        assertThat(found).isNotEmpty();
        // A gap means a migration was deleted or misnumbered. Flyway tolerates it,
        // but it hides the kind of renumbering mistake this class exists to catch.
        assertThat(found)
                .withFailMessage("Migration versions should run 1..%d with no gaps, but found: %s",
                        found.size(), found)
                .isEqualTo(IntStream.rangeClosed(1, found.size()).boxed().toList());
    }
}
