package dk.itu.datasys;

import static dk.itu.datasys.Specifications.ColumnType.DOUBLE;
import static dk.itu.datasys.Specifications.ColumnType.LONG;
import static dk.itu.datasys.Specifications.ColumnType.STRING;
import static dk.itu.datasys.Specifications.Comparison.GREATER_THAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.operators.FilterOperator;
import dk.itu.datasys.operators.Operator;
import dk.itu.datasys.operators.ScanOperator;

class OperatorTests {
    private static final List<ColumnSpec> TRIP_SCHEMA = List.of(
            new ColumnSpec("city", STRING),
            new ColumnSpec("distance", LONG),
            new ColumnSpec("price", DOUBLE));

    @TempDir
    Path tempDir;

    @Test
    void filterOperatorEmitsOnlyMatchingRows() throws IOException {
        StorageEngine engine = new StorageEngine(tempDir, 2);
        List<Object[]> rows = loadTripsRows();

        FilterOperator filter = new FilterOperator(
                new TestListOperator(TRIP_SCHEMA, rows),
                new Predicate("distance", GREATER_THAN, 50L));

        List<Object[]> actual = drain(filter);

        assertEquals(6, actual.size());
        assertArrayEquals(new Object[] { "Aarhus", 187L, 301.0 }, actual.get(0));
        assertArrayEquals(new Object[] { "Odense", 95L, 120.75 }, actual.get(1));
        assertArrayEquals(new Object[] { "Esbjerg", 299L, 450.25 }, actual.get(actual.size() - 1));
    }

    @Test
    void scanOperatorReturnsExactlyTheRowsInItsPartitionsAndEmptyCaseReturnsNothing() throws IOException {
        StorageEngine engine = new StorageEngine(tempDir, 2);
        engine.createTable("trips", TRIP_SCHEMA);
        engine.copyFile("trips", Path.of("src", "test", "resources", "trips.csv").toString());

        List<Object[]> expected = loadTripsRows();
        List<Object[]> actual = drain(new ScanOperator(engine, "trips", engine.testCatalog().tables.get("trips").partitions));

        assertEquals(expected.size(), actual.size());
        assertArrayEquals(expected.get(0), actual.get(0));
        assertArrayEquals(expected.get(expected.size() - 1), actual.get(actual.size() - 1));

        List<Object[]> empty = drain(new ScanOperator(engine, "trips", List.of()));
        assertTrue(empty.isEmpty());
    }

    private static List<Object[]> loadTripsRows() throws IOException {
        List<Object[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src", "test", "resources", "trips.csv"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(new StorageEngine(Path.of("data")).parseCsvLine(line, TRIP_SCHEMA, "trips.csv", rows.size() + 1));
            }
        }
        return rows;
    }

    private static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        try {
            Object[] row;
            while ((row = operator.next()) != null) {
                rows.add(row);
            }
        } finally {
            operator.close();
        }
        return rows;
    }

    private static final class TestListOperator implements Operator {
        private final List<ColumnSpec> schema;
        private final List<Object[]> rows;
        private int index;

        private TestListOperator(List<ColumnSpec> schema, List<Object[]> rows) {
            this.schema = schema;
            this.rows = rows;
        }

        @Override
        public List<ColumnSpec> schema() {
            return schema;
        }

        @Override
        public void open() {
            index = 0;
        }

        @Override
        public Object[] next() {
            if (index >= rows.size()) {
                return null;
            }
            return rows.get(index++);
        }

        @Override
        public void close() {
        }
    }
}
