package dk.itu.datasys;

import static dk.itu.datasys.Specifications.*;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.Specifications.Comparison;
import dk.itu.datasys.StorageEngine;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageEngineUnitTests {

    @TempDir
    Path dataDirectory;
    
    @Test
    void valueEncodingRoundTripsEachColumnType() throws Exception {
        Path tempFile = dataDirectory.resolve("temp.bin");
        
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tempFile))) {

            StorageEngine.writeValue(out, ColumnType.STRING, "Aarhus");
            StorageEngine.writeValue(out, ColumnType.LONG, -42L);
            StorageEngine.writeValue(out, ColumnType.DOUBLE, 23.5);
        }

        try (RandomAccessFile in = new RandomAccessFile(tempFile.toString(), "r")) {

            assertEquals(StorageEngine.readValue(in, ColumnType.STRING), "Aarhus");
            assertEquals(StorageEngine.readValue(in, ColumnType.LONG), -42L);
            assertEquals(StorageEngine.readValue(in, ColumnType.DOUBLE), 23.5);
        }
    }   

    @Test
    void minMaxHandlesSingleNegativeAndStringValues() throws Exception {

        StorageEngine engine = new StorageEngine(dataDirectory);

        // Only two columns needed for this test
        List<ColumnSpec> columns = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG));

        engine.createTable("trips", columns);

        StorageEngine.TableDefinition table =
            engine.testCatalog().tables.get("trips");

        List<Object[]> singleRow = List.<Object[]>of(
            new Object[] { "Aarhus", 10L });

        List<Object[]> multipleRows = List.<Object[]>of(
            new Object[] { "Copenhagen", 10L },
            new Object[] { "Odense", 100L },
            new Object[] { "Aalborg", 50L });

        List<Object[]> negativeRow = List.<Object[]>of(
            new Object[] { "Copenhagen", -42L });

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dataDirectory.resolve("temp.bin")))) {

            long offset = engine.writePartition(out, singleRow, "trips", columns, table.partitions, 0L);

            // Check only 1 partition exists
            assertEquals(1, table.partitions.size());
            
            //Check min/max is the same for single-value cases
            assertEquals("Aarhus",
                table.partitions.get(0).columns.get("city").min.asText());
            assertEquals("Aarhus",
                table.partitions.get(0).columns.get("city").max.asText());

            int multiplePartitionIndex = table.partitions.size();
            offset = engine.writePartition(out, multipleRows, "trips", columns, table.partitions, offset);

            //Check min/max with several values
            assertEquals(10L, table.partitions.get(multiplePartitionIndex)
                .columns.get("distance").min.asLong());
            assertEquals(100L, table.partitions.get(multiplePartitionIndex)
                .columns.get("distance").max.asLong());

            int negativePartitionIndex = table.partitions.size();
            engine.writePartition(
                out, negativeRow, "trips", columns, table.partitions, offset);

            //Check for negative long value case
            assertEquals(-42L, table.partitions.get(negativePartitionIndex)
                .columns.get("distance").min.asLong());
            assertEquals(-42L, table.partitions.get(negativePartitionIndex)
                .columns.get("distance").max.asLong());
        }
    }
    

    @Test
    void pruningDecisionCoversEveryComparison() throws Exception {
        StorageEngine engine = new StorageEngine(Files.createTempDirectory("storage-unit-"));
        assertTrue(engine.cannotMatch(ColumnType.LONG, Comparison.EQUALS, 1L, 5L, 10L));
        assertTrue(!engine.cannotMatch(ColumnType.LONG, Comparison.EQUALS, 7L, 5L, 10L));
        assertTrue(engine.cannotMatch(ColumnType.LONG, Comparison.LESS_THAN, 5L, 5L, 10L));
        assertTrue(!engine.cannotMatch(ColumnType.LONG, Comparison.LESS_THAN, 6L, 5L, 10L));
        assertTrue(engine.cannotMatch(ColumnType.LONG, Comparison.GREATER_THAN, 10L, 5L, 10L));
        assertTrue(!engine.cannotMatch(ColumnType.LONG, Comparison.GREATER_THAN, 9L, 5L, 10L));
    }

    @Test
    void csvLineParsingTypesValuesAndErrors() throws Exception {
        StorageEngine engine = new StorageEngine(Files.createTempDirectory("storage-unit-"));
        List<ColumnSpec> columns = List.of(new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));
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