package dk.itu.datasys;

import static dk.itu.datasys.Specifications.ColumnType.DOUBLE;
import static dk.itu.datasys.Specifications.ColumnType.LONG;
import static dk.itu.datasys.Specifications.ColumnType.STRING;
import static dk.itu.datasys.Specifications.Comparison.GREATER_THAN;
import static dk.itu.datasys.Specifications.Comparison.LESS_THAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.Statement.SelectStatement;
import dk.itu.datasys.operators.FilterOperator;
import dk.itu.datasys.operators.Operator;
import dk.itu.datasys.operators.ScanOperator;

class PlannerTests {
    private static final List<ColumnSpec> TRIP_SCHEMA = List.of(
            new ColumnSpec("city", STRING),
            new ColumnSpec("distance", LONG),
            new ColumnSpec("price", DOUBLE));

    @TempDir
    Path tempDir;

    @Test
    void plannerPrunesPartitionsToOnlyThoseThatCouldMatch() throws IOException {
        StorageEngine engine = new StorageEngine(tempDir, 2);
        engine.createTable("trips", TRIP_SCHEMA);
        engine.copyFile("trips", Path.of("src", "test", "resources", "trips.csv").toString());

        Planner planner = new Planner(engine);
        Operator plan = planner.plan(new SelectStatement("trips",
                Optional.of(new Predicate("distance", LESS_THAN, 20L))));

        assertInstanceOf(FilterOperator.class, plan);
        assertInstanceOf(ScanOperator.class, childOf((FilterOperator) plan));

        List<Object[]> matches = drain(plan);
        assertEquals(1, matches.size());
        assertEquals("Copenhagen", matches.get(0)[0]);
        assertEquals(12L, matches.get(0)[1]);

        StorageEngine.ScanStats stats = engine.getLastScanStats();
        assertEquals(4, stats.partitionsTotal());
        assertEquals(1, stats.partitionsRead());
        assertEquals(3, stats.partitionsPruned());
    }

    @Test
    void plannerShapesAreFilterOverScanForWhereAndBareScanWithoutWhere() throws IOException {
        StorageEngine engine = new StorageEngine(tempDir, 2);
        engine.createTable("trips", TRIP_SCHEMA);
        engine.copyFile("trips", Path.of("src", "test", "resources", "trips.csv").toString());

        Planner planner = new Planner(engine);

        Operator withWhere = planner.plan(new SelectStatement("trips",
                Optional.of(new Predicate("distance", GREATER_THAN, 50L))));
        assertInstanceOf(FilterOperator.class, withWhere);
        assertInstanceOf(ScanOperator.class, childOf((FilterOperator) withWhere));

        Operator withoutWhere = planner.plan(new SelectStatement("trips", Optional.empty()));
        assertInstanceOf(ScanOperator.class, withoutWhere);
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

    private static Operator childOf(FilterOperator filter) {
        try {
            Field child = FilterOperator.class.getDeclaredField("child");
            child.setAccessible(true);
            return (Operator) child.get(filter);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to inspect FilterOperator child", exception);
        }
    }
}
