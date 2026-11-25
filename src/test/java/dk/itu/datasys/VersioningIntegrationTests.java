package dk.itu.datasys;

import static dk.itu.datasys.Specifications.ColumnType.DOUBLE;
import static dk.itu.datasys.Specifications.ColumnType.LONG;
import static dk.itu.datasys.Specifications.ColumnType.STRING;
import static dk.itu.datasys.Specifications.Comparison.EQUALS;
import static dk.itu.datasys.Specifications.Comparison.GREATER_THAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Statement.Predicate;

class VersioningIntegrationTests {
    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", STRING),
            new ColumnSpec("distance", LONG),
            new ColumnSpec("price", DOUBLE));

    @TempDir
    Path dataDirectory;

    @Test
    void createTwoCopiesAndDeleteAdvanceVersionsAndSelectEachState() throws Exception {
        StorageEngine engine = new StorageEngine(dataDirectory, 2);
        engine.createTable("trips", SCHEMA);
        assertEquals(0L, engine.tableVersion("trips"));
        assertEquals(0, engine.select("trips", "distance", GREATER_THAN, -1L).size());

        Path first = dataDirectory.resolve("first.csv");
        Files.writeString(first, "A,10,1.0\nB,20,2.0\n");
        engine.copyFile("trips", first.toString());
        assertEquals(1L, engine.tableVersion("trips"));
        List<Catalog.Partition> versionOne = engine.testCatalog().tables.get("trips")
                .getPartitionsByVersion().get(1);
        Catalog.Snapshot versionOneSnapshot = engine.testCatalog().tables.get("trips").snapshot();
        assertSame(versionOne, versionOneSnapshot.partitions());
        assertEquals(List.of("A", "B"),
                engine.select("trips", "distance", GREATER_THAN, -1L).stream()
                        .map(row -> (String) row[0]).toList());

        Path second = dataDirectory.resolve("second.csv");
        Files.writeString(second, "C,30,3.0\nD,40,4.0\n");
        engine.copyFile("trips", second.toString());
        assertEquals(2L, engine.tableVersion("trips"));
        List<Catalog.Partition> versionTwo = engine.testCatalog().tables.get("trips")
                .getPartitionsByVersion().get(2);
        assertEquals(1, versionOneSnapshot.version());
        assertSame(versionOne, versionOneSnapshot.partitions());
        assertNotSame(versionOne, versionTwo);
        assertSame(versionOne.get(0), versionTwo.get(0));
        assertThrows(UnsupportedOperationException.class, () -> versionOne.add(versionOne.get(0)));
        assertEquals(List.of("A", "B", "C", "D"),
                engine.select("trips", "distance", GREATER_THAN, -1L).stream()
                        .map(row -> (String) row[0]).toList());

        engine.deleteRows("trips", new Predicate("city", EQUALS, "B"));
        assertEquals(3L, engine.tableVersion("trips"));
        assertThrows(Catalog.ConflictException.class,
                () -> engine.testCatalog().tables.get("trips")
                        .commit(2, versionTwo));
        assertEquals(List.of("A", "C", "D"),
                engine.select("trips", "distance", GREATER_THAN, -1L).stream()
                        .map(row -> (String) row[0]).toList());
        engine.close();
    }

    @Test
    void selectiveDeleteRewritesCandidateAndCarriesPartitionOffsets() throws Exception {
        StorageEngine engine = new StorageEngine(dataDirectory, 2);
        engine.createTable("trips", SCHEMA);
        Path csv = dataDirectory.resolve("sorted.csv");
        Files.writeString(csv,
                "A,10,1.0\nB,20,2.0\nC,30,3.0\nD,40,4.0\nE,50,5.0\nF,60,6.0\n");
        engine.copyFile("trips", csv.toString());

        StorageEngine.TableDefinition before = engine.snapshotTable("trips");
        long offset0 = before.partitions.get(0).offset;
        long offset1 = before.partitions.get(1).offset;
        long offset2 = before.partitions.get(2).offset;

        engine.deleteRows("trips", new Predicate("distance", EQUALS, 40L));

        StorageEngine.TableDefinition after = engine.snapshotTable("trips");
        assertEquals(2L, after.version);
        assertEquals(offset0, after.partitions.get(0).offset);
        assertEquals(offset2, after.partitions.get(2).offset);
        assertNotEquals(offset1, after.partitions.get(1).offset);
        assertTrue(after.partitions.get(1).offset > offset1);
        assertEquals(5, engine.select("trips", "distance", GREATER_THAN, -1L).size());

        engine.close();
        StorageEngine restarted = new StorageEngine(dataDirectory, 2);
        assertEquals(2L, restarted.tableVersion("trips"));
        assertEquals(5, restarted.select("trips", "distance", GREATER_THAN, -1L).size());
        Path third = dataDirectory.resolve("third.csv");
        Files.writeString(third, "G,70,7.0\n");
        restarted.copyFile("trips", third.toString());
        assertEquals(3L, restarted.tableVersion("trips"));
        restarted.close();
    }
}
