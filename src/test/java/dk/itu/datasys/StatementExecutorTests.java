package dk.itu.datasys;

import static dk.itu.datasys.Specifications.ColumnType.DOUBLE;
import static dk.itu.datasys.Specifications.ColumnType.LONG;
import static dk.itu.datasys.Specifications.ColumnType.STRING;
import static dk.itu.datasys.Specifications.Comparison.GREATER_THAN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.Statement.SelectStatement;

class StatementExecutorTests {
    private static final List<ColumnSpec> TRIP_SCHEMA = List.of(
            new ColumnSpec("city", STRING),
            new ColumnSpec("distance", LONG),
            new ColumnSpec("price", DOUBLE));

    @Test
    void executeSelectReturnsRowsMatchingPredicate(@TempDir Path tempDir) {
        StatementExecutor executor = new StatementExecutor(new StorageEngine(tempDir, 2));
        executor.execute(new CreateTableStatement("trips", TRIP_SCHEMA));
        executor.execute(new CopyStatement(
                "trips",
                Path.of("src", "test", "resources", "trips.csv").toAbsolutePath().toString()));

        List<Object[]> rows = executor.execute(new SelectStatement(
                "trips",
                Optional.of(new Predicate("distance", GREATER_THAN, 100L))));

        assertEquals(4, rows.size());
        assertEquals("Aarhus", rows.get(0)[0]);
        assertEquals(187L, rows.get(0)[1]);
        assertEquals("Aalborg", rows.get(2)[0]);
        assertEquals(299L, rows.get(3)[1]);
    }
}
