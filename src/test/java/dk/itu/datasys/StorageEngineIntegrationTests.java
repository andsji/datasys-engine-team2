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

class StorageEngineIntegrationTests {
    private static final List<ColumnSpec> TRIP_SCHEMA = List.of(
            new ColumnSpec("city", STRING), new ColumnSpec("distance", LONG),
            new ColumnSpec("price", DOUBLE));

    @TempDir
    Path dataDirectory;

    @Test
    void schemaPersistsAndDuplicateTableIsRejected() {
        new StorageEngine(dataDirectory).createTable("trips", TRIP_SCHEMA);
        assertThrows(IllegalArgumentException.class,
                () -> new StorageEngine(dataDirectory).createTable("trips", TRIP_SCHEMA));
        assertTrue(Files.exists(dataDirectory.resolve("catalog/catalog.json")));
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
        JsonNode catalog = new ObjectMapper().readTree(
                dataDirectory.resolve("catalog/catalog.json").toFile());
        JsonNode partitions = catalog.path("tables").path("trips").path("partitions");
        assertEquals(4, partitions.size());
        assertEquals(12L, partitions.get(0).path("columns").path("distance").path("min").asLong());
        assertEquals(187L, partitions.get(0).path("columns").path("distance").path("max").asLong());

        List<Object[]> rows = engine.select("trips", "distance", GREATER_THAN, 200L);
        assertEquals(2, rows.size());
        assertTrue(engine.getLastScanStats().partitionsPruned() >= 2);
    }

    @Test
    void copiedDataSurvivesRestart() throws Exception {
        loadGoldenFile(2);
        StorageEngine restarted = new StorageEngine(dataDirectory);
        assertEquals(8, restarted.select("trips", "distance", GREATER_THAN, -1L).size());
    }

    private StorageEngine loadGoldenFile(int partitionSize) throws Exception {
        StorageEngine engine = new StorageEngine(dataDirectory, partitionSize);
        engine.createTable("trips", TRIP_SCHEMA);
        engine.copyFile("trips", Path.of("src", "test", "resources", "trips.csv").toString());
        return engine;
    }
}