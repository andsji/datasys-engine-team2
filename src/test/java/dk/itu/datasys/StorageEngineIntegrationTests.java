package dk.itu.datasys;

import static dk.itu.datasys.Specifications.ColumnType.DOUBLE;
import static dk.itu.datasys.Specifications.ColumnType.LONG;
import static dk.itu.datasys.Specifications.ColumnType.STRING;
import static dk.itu.datasys.Specifications.Comparison.EQUALS;
import static dk.itu.datasys.Specifications.Comparison.GREATER_THAN;
import static dk.itu.datasys.Specifications.Comparison.LESS_THAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.StorageEngine.TableDefinition;
import dk.itu.datasys.Catalog.Partition;

class StorageEngineIntegrationTests {
    private static final List<ColumnSpec> TRIP_SCHEMA = List.of(
            new ColumnSpec("city", STRING), new ColumnSpec("distance", LONG),
            new ColumnSpec("price", DOUBLE));

    @TempDir
    Path dataDirectory;

    @Test
    void schemaPersistsAndDuplicateTableIsRejected() {
        StorageEngine engine = new StorageEngine(dataDirectory);
        engine.createTable("trips", TRIP_SCHEMA);
        assertTrue(!Files.exists(dataDirectory.resolve("catalog/catalog.json")));
        engine.close();
        assertTrue(Files.exists(dataDirectory.resolve("catalog/catalog.json")));
        StorageEngine restarted = new StorageEngine(dataDirectory);
        assertThrows(IllegalArgumentException.class,
            () -> restarted.createTable("trips", TRIP_SCHEMA));
        restarted.close();
    }

        @Test
        void legacyCatalogLoadsItsVersionAndIsWrittenInVersionedFormatOnClose() throws Exception {
                Path catalogDirectory = dataDirectory.resolve("catalog");
                Files.createDirectories(catalogDirectory);
                Files.writeString(catalogDirectory.resolve("catalog.json"), """
                                {
                                    "tables": {
                                        "trips": {
                                            "version": 5,
                                            "columns": [],
                                            "dataFile": null,
                                            "partitions": []
                                        }
                                    }
                                }
                                """);

                StorageEngine engine = new StorageEngine(dataDirectory);
                assertEquals(5L, engine.tableVersion("trips"));
                engine.close();

                JsonNode catalog = new ObjectMapper().readTree(
                                catalogDirectory.resolve("catalog.json").toFile());
                assertTrue(catalog.path("tables").path("trips").path("partitionsByVersion")
                                .has("5"));
        }

    @Test
    void goldenFileRoundTripsAllRowsAndTypes() throws Exception {
        StorageEngine engine = loadGoldenFile(2);
        List<Object[]> rows = engine.select("trips", "distance", GREATER_THAN, -1L);
        assertEquals(8, rows.size());
        assertEquals("Copenhagen", rows.get(0)[0]);
        assertEquals(12L, rows.get(0)[1]);
        assertEquals(23.5, rows.get(0)[2]);
    }

    @Test
    void allComparisonsWorkForStringLongAndDouble() throws Exception {
        StorageEngine engine = loadGoldenFile(2);
        assertEquals(3, engine.select("trips", "city", EQUALS, "Copenhagen").size());
        assertEquals(6, engine.select("trips", "city", LESS_THAN, "Odense").size());
        assertEquals(3, engine.select("trips", "city", GREATER_THAN, "Copenhagen").size());
        assertEquals(1, engine.select("trips", "distance", EQUALS, 187L).size());
        assertEquals(4, engine.select("trips", "distance", LESS_THAN, 100L).size());
        assertEquals(4, engine.select("trips", "distance", GREATER_THAN, 100L).size());
        assertEquals(1, engine.select("trips", "price", EQUALS, 23.5).size());
        assertEquals(2, engine.select("trips", "price", LESS_THAN, 50.0).size());
        assertEquals(5, engine.select("trips", "price", GREATER_THAN, 100.0).size());
    }

    @Test
    void emptyResultsAndInvalidArgumentsAreHandled() throws Exception {
        StorageEngine engine = loadGoldenFile(2);
        assertTrue(engine.select("trips", "distance", EQUALS, 999L).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("unknown", "distance", EQUALS, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "unknown", EQUALS, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "distance", EQUALS, 1));
    }

    @Test
    void partitionMetadataAndPruningArePersisted() throws Exception {
        StorageEngine engine = loadGoldenFile(2);
        List<Object[]> rows = engine.select("trips", "distance", GREATER_THAN, 200L);
        assertEquals(2, rows.size());
        assertTrue(engine.getLastScanStats().partitionsPruned() >= 2);
        engine.close();
        JsonNode catalog = new ObjectMapper().readTree(
                dataDirectory.resolve("catalog/catalog.json").toFile());
        JsonNode partitions = catalog.path("tables").path("trips")
            .path("partitionsByVersion").path("1");
        assertEquals(4, partitions.size());
        assertEquals(12L, partitions.get(0).path("columns").path("distance").path("min").asLong());
        assertEquals(187L, partitions.get(0).path("columns").path("distance").path("max").asLong());
    }

    @Test
    void copiedDataSurvivesRestart() throws Exception {
        loadGoldenFile(2).close();
        StorageEngine restarted = new StorageEngine(dataDirectory);
        assertEquals(8, restarted.select("trips", "distance", GREATER_THAN, -1L).size());
        restarted.close();
    }

    @Test
    void repeatedCopyAppendsAndVersionsCatalog() throws Exception {
        StorageEngine engine = new StorageEngine(dataDirectory, 2);
        engine.createTable("trips", TRIP_SCHEMA);
        assertEquals(0L, engine.tableVersion("trips"));

        Path first = dataDirectory.resolve("first.csv");
        Files.writeString(first, "Aalborg,12,23.5\nOdense,95,120.75\n");
        engine.copyFile("trips", first.toString());
        assertEquals(1L, engine.tableVersion("trips"));

        Path second = dataDirectory.resolve("second.csv");
        Files.writeString(second, "Roskilde,187,450.25\nCopenhagen,299,300.0\n");
        engine.copyFile("trips", second.toString());
        assertEquals(2L, engine.tableVersion("trips"));

        engine.close();
        StorageEngine restarted = new StorageEngine(dataDirectory, 2);
        assertEquals(2L, restarted.tableVersion("trips"));
        assertEquals(4, restarted.select("trips", "distance", GREATER_THAN, -1L).size());
        assertEquals(2, restarted.testCatalog().tables.get("trips").snapshot().partitions().size());
        restarted.close();
    }

    @Test
    void emptyCopyIsANoop() throws Exception {
        StorageEngine engine = new StorageEngine(dataDirectory);
        engine.createTable("trips", TRIP_SCHEMA);
        Path empty = dataDirectory.resolve("empty.csv");
        Files.writeString(empty, "");
        engine.copyFile("trips", empty.toString());
        assertEquals(1L, engine.tableVersion("trips"));
        assertTrue(engine.testCatalog().tables.get("trips").snapshot().partitions().isEmpty());
        engine.close();
    }

    @Test
    void selectiveDeleteRewritesOnlyCandidatePartitionsAndCarriesOffsets() throws Exception {
        StorageEngine engine = loadGoldenFile(2);
        TableDefinition before = engine.snapshotTable("trips");
        long originalVersion = before.version;
        long[] carriedOffsets = new long[] { before.partitions.get(0).offset,
                before.partitions.get(1).offset, before.partitions.get(2).offset,
                before.partitions.get(3).offset };

        engine.deleteRows("trips", new Predicate("distance", EQUALS, 95L));

        TableDefinition after = engine.snapshotTable("trips");
        assertEquals(originalVersion + 1, after.version);
        assertEquals(4, after.partitions.size());

        assertEquals(carriedOffsets[0], after.partitions.get(0).offset);
        assertEquals(carriedOffsets[2], after.partitions.get(2).offset);
        assertEquals(carriedOffsets[3], after.partitions.get(3).offset);

        boolean foundReplacementWithRows = false;
        for (Partition partition : after.partitions) {
            if (partition.offset != carriedOffsets[0]
                    && partition.offset != carriedOffsets[1]
                    && partition.offset != carriedOffsets[2]
                    && partition.offset != carriedOffsets[3]) {
                foundReplacementWithRows = true;
            }
        }
        assertTrue(foundReplacementWithRows);

        engine.close();
        StorageEngine restarted = new StorageEngine(dataDirectory, 2);
        assertEquals(after.version, restarted.tableVersion("trips"));
        assertEquals(7, restarted.select("trips", "distance", GREATER_THAN, -1L).size());
        restarted.close();
    }

    @Test
    void deleteWithNoMatchIsANoop() throws Exception {
        StorageEngine engine = loadGoldenFile(2);
        long beforeVersion = engine.tableVersion("trips");
        int beforePartitions = engine.snapshotTable("trips").partitions.size();

        engine.deleteRows("trips", new Predicate("distance", EQUALS, 999L));

        assertEquals(beforeVersion + 1, engine.tableVersion("trips"));
        assertEquals(beforePartitions, engine.snapshotTable("trips").partitions.size());
    }

    private StorageEngine loadGoldenFile(int partitionSize) throws Exception {
        StorageEngine engine = new StorageEngine(dataDirectory, partitionSize);
        engine.createTable("trips", TRIP_SCHEMA);
        engine.copyFile("trips", Path.of("src", "test", "resources", "trips.csv").toString());
        return engine;
    }
}