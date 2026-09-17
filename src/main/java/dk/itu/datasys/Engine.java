package dk.itu.datasys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.Specifications.Comparison;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        Path demoDirectory = null;
        try {
            demoDirectory = Files.createTempDirectory("datasys-demo-");
            Path input = Path.of("src", "test", "resources", "trips.csv");

            StorageEngine storage = new StorageEngine(demoDirectory);
            storage.createTable("trips", List.of(
                    new ColumnSpec("city", ColumnType.STRING),
                    new ColumnSpec("distance", ColumnType.LONG),
                    new ColumnSpec("price", ColumnType.DOUBLE)));
            storage.copyFile("trips", input.toString());

            printResults("distance > 100", storage.select(
                    "trips", "distance", Comparison.GREATER_THAN, 100L));
            printResults("city = Copenhagen", storage.select(
                    "trips", "city", Comparison.EQUALS, "Copenhagen"));
            printResults("price < 50.0", storage.select(
                    "trips", "price", Comparison.LESS_THAN, 50.0));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not run golden storage demo", exception);
        } finally {
            deleteRecursively(demoDirectory);
            LOGGER.debug("engine stopped");
            MDC.clear();
        }
    }

    private static void printResults(String predicate, List<Object[]> rows) {
        System.out.println(predicate + ":");
        for (Object[] row : rows) {
            System.out.println("  " + Arrays.toString(row));
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOGGER.warn("Could not delete demo path {}", path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.warn("Could not clean up demo directory {}", directory, exception);
        }
    }

    String teamName() {
        return "team2";
    }
}