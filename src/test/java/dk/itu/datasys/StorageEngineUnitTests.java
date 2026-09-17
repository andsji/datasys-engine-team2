package dk.itu.datasys;

import static dk.itu.datasys.Specifications.ColumnType.DOUBLE;
import static dk.itu.datasys.Specifications.ColumnType.LONG;
import static dk.itu.datasys.Specifications.ColumnType.STRING;
import static dk.itu.datasys.Specifications.Comparison.EQUALS;
import static dk.itu.datasys.Specifications.Comparison.GREATER_THAN;
import static dk.itu.datasys.Specifications.Comparison.LESS_THAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Specifications.ColumnSpec;

class StorageEngineUnitTests {
    @Test
    void valueEncodingRoundTripsEachColumnType() throws Exception {
        StorageEngine engine = new StorageEngine(Files.createTempDirectory("storage-unit-"));
        assertRoundTrip(engine, STRING, "Aarhus");
        assertRoundTrip(engine, LONG, -42L);
        assertRoundTrip(engine, DOUBLE, 23.5);
    }

    private void assertRoundTrip(StorageEngine engine, Specifications.ColumnType type,
            Object expected) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            engine.writeValue(output, type, expected);
        }
        Object actual;
        Path file = tempFile(bytes);
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file.toFile(), "r")) {
            actual = engine.readValue(input, type);
        }
        Files.deleteIfExists(file);
        assertEquals(expected, actual);
    }

    private Path tempFile(ByteArrayOutputStream bytes) throws Exception {
        Path file = Files.createTempFile("encoded-value-", ".bin");
        Files.write(file, bytes.toByteArray());
        return file;
    }

    @Test
    void minMaxHandlesSingleNegativeAndStringValues() throws Exception {
        StorageEngine engine = new StorageEngine(Files.createTempDirectory("storage-unit-"));
        assertEquals(new StorageEngine.MinMax(-7L, -2L), engine.minMax(LONG, List.of(-2L, -7L)));
        assertEquals(new StorageEngine.MinMax(4.5, 4.5), engine.minMax(DOUBLE, List.of(4.5)));
        assertEquals(new StorageEngine.MinMax("Aalborg", "Odense"),
                engine.minMax(STRING, List.of("Odense", "Aalborg")));
    }

    @Test
    void pruningDecisionCoversEveryComparison() throws Exception {
        StorageEngine engine = new StorageEngine(Files.createTempDirectory("storage-unit-"));
        assertTrue(engine.cannotMatch(LONG, EQUALS, 1L, 5L, 10L));
        assertTrue(!engine.cannotMatch(LONG, EQUALS, 7L, 5L, 10L));
        assertTrue(engine.cannotMatch(LONG, LESS_THAN, 5L, 5L, 10L));
        assertTrue(!engine.cannotMatch(LONG, LESS_THAN, 6L, 5L, 10L));
        assertTrue(engine.cannotMatch(LONG, GREATER_THAN, 10L, 5L, 10L));
        assertTrue(!engine.cannotMatch(LONG, GREATER_THAN, 9L, 5L, 10L));
    }

    @Test
    void csvLineParsingTypesValuesAndErrors() throws Exception {
        StorageEngine engine = new StorageEngine(Files.createTempDirectory("storage-unit-"));
        List<ColumnSpec> columns = List.of(new ColumnSpec("city", STRING),
                new ColumnSpec("distance", LONG), new ColumnSpec("price", DOUBLE));
        assertArrayEquals(new Object[] { "Aarhus", 187L, 301.0 },
                engine.parseCsvLine("Aarhus,187,301.0", columns, "trips.csv", 1));

        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> engine.parseCsvLine("Aarhus,nope,301.0", columns, "trips.csv", 2));
        assertTrue(malformed.getMessage().contains("trips.csv")
                && malformed.getMessage().contains("line 2"));

        IllegalArgumentException wrongCount = assertThrows(IllegalArgumentException.class,
                () -> engine.parseCsvLine("Aarhus,187", columns, "trips.csv", 3));
        assertTrue(wrongCount.getMessage().contains("trips.csv")
                && wrongCount.getMessage().contains("line 3"));
    }
}