package dk.itu.datasys;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import dk.itu.datasys.Specifications.*;

public class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final int FILE_MAGIC = 0x44534231;
    private static final int FILE_VERSION = 1;
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 1000;

    private final Path dataDirectory;
    private final Path catalogPath;
    private final Catalog catalogFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRowsPerPartition;
    private ScanStats lastScanStats = new ScanStats(0, 0, 0);
    

    /** All persistent state (catalog + data files) lives under this directory. */
    public StorageEngine(Path dataDirectory) { 
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        if (maxRowsPerPartition <= 0) {
            throw new IllegalArgumentException("maxRowsPerPartition must be positive");
        }
        this.maxRowsPerPartition = maxRowsPerPartition;
        this.catalogPath = dataDirectory.resolve("catalog").resolve("catalog.json");
        try {
            Files.createDirectories(catalogPath.getParent());
            if (Files.exists(catalogPath)) {
                this.catalogFile = objectMapper.readValue(
                        catalogPath.toFile(), new TypeReference<Catalog>() { });
            } else {
                this.catalogFile = new Catalog();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load catalog " + catalogPath, exception);
        }
    }

    public void createTable(String tableName, List<ColumnSpec> columns) throws IllegalArgumentException{ 
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name must not be blank");
        }
        if (catalogFile.tables.containsKey(tableName)) {
            throw new IllegalArgumentException("Table already exists: " + tableName);
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("A table must have at least one column");
        }

        Set<String> columnNames = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.type() == null
                    || !columnNames.add(column.name())) {
                throw new IllegalArgumentException("Column names must be non-null and unique");
            }
        }

        catalogFile.tables.put(tableName, new TableDefinition(columns));
        persistCatalog();
    }

    private void persistCatalog() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(catalogPath.toFile(), catalogFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist catalog " + catalogPath, exception);
        }
    }

    Catalog testCatalog() {
        return catalogFile;
    }

    public static final class Catalog {
        @JsonProperty("tables")
        public Map<String, TableDefinition> tables = new LinkedHashMap<>();

        public Catalog() {
        }
    }

    public static final class TableDefinition {
        @JsonProperty("columns")
        public List<ColumnSpec> columns;

        @JsonProperty("dataFile")
        public String dataFile;

        @JsonProperty("partitions")
        public List<PartitionDefinition> partitions = new ArrayList<>();

        public TableDefinition() {
        }

        public TableDefinition(List<ColumnSpec> columns) {
            this.columns = new ArrayList<>(columns);
        }
    }

    public static final class PartitionDefinition {
        @JsonProperty("offset")
        public long offset;

        @JsonProperty("rowCount")
        public int rowCount;

        @JsonProperty("columns")
        public Map<String, ColumnStats> columns = new LinkedHashMap<>();

        public PartitionDefinition() {
        }
    }

    public static final class ColumnStats {
        @JsonProperty("min")
        public JsonNode min;

        @JsonProperty("max")
        public JsonNode max;

        public ColumnStats() {
        }

        public ColumnStats(JsonNode min, JsonNode max) {
            this.min = min;
            this.max = max;
        }
    }
    
    public void copyFile(String tableName, String csvFilePath) { 
        TableDefinition table = catalogFile.tables.get(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        if (!table.partitions.isEmpty() || table.dataFile != null) {
            throw new UnsupportedOperationException("Table already has data: " + tableName);
        }

        Path source = Path.of(csvFilePath);
        Path dataDirectoryPath = dataDirectory.resolve("data");
        Path dataFile = dataDirectoryPath.resolve(tableName + ".bin");
        Path temporaryFile = dataDirectoryPath.resolve(tableName + ".bin.tmp");
        List<PartitionDefinition> partitions = new ArrayList<>();
        int rowCount = 0;
        long bytesWritten = 0;

        try {
            Files.createDirectories(dataDirectoryPath);
            try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.US_ASCII);
                    DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporaryFile))) {
                output.writeInt(FILE_MAGIC);
                output.writeInt(FILE_VERSION);
                bytesWritten = 8;

                List<Object[]> rows = new ArrayList<>(maxRowsPerPartition);
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    Object[] row = parseCsvLine(line, table.columns, csvFilePath, lineNumber);
                    rows.add(row);
                    if (rows.size() == maxRowsPerPartition) {
                        bytesWritten = writePartition(output, rows, tableName, table.columns, partitions, bytesWritten);
                        rowCount += rows.size();
                        rows.clear();
                    }
                }
                if (!rows.isEmpty()) {
                    bytesWritten = writePartition(output, rows, tableName, table.columns, partitions, bytesWritten);
                    rowCount += rows.size();
                }
            }

                moveDataFile(temporaryFile, dataFile);
            table.dataFile = dataDirectory.relativize(dataFile).toString();
            table.partitions = partitions;
            persistCatalog();
            LOGGER.debug("table={} file={} rows={} partitions={} durationMs=0",
                    tableName, csvFilePath, rowCount, partitions.size());
        } catch (IllegalArgumentException exception) {
            deleteIfExists(temporaryFile);
            throw exception;
        } catch (IOException exception) {
            deleteIfExists(temporaryFile);
            throw new IllegalArgumentException("Could not copy " + csvFilePath, exception);
        }
    }

    Object[] parseCsvLine(String line, List<ColumnSpec> columns,
            String csvFilePath, int lineNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != columns.size()) {
            throw parseError(csvFilePath, lineNumber,
                    "expected " + columns.size() + " fields but found " + fields.length);
        }

        Object[] row = new Object[columns.size()];
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            String value = fields[columnIndex];
            if (!value.chars().allMatch(character -> character <= 127)) {
                throw parseError(csvFilePath, lineNumber, "value is not ASCII");
            }
            try {
                row[columnIndex] = switch (columns.get(columnIndex).type()) {
                    case STRING -> value;
                    case LONG -> Long.parseLong(value);
                    case DOUBLE -> Double.parseDouble(value);
                };
            } catch (NumberFormatException exception) {
                throw parseError(csvFilePath, lineNumber,
                        "invalid " + columns.get(columnIndex).type() + " value");
            }
        }
        return row;
    }

        public long writePartition(DataOutputStream output, List<Object[]> rows, String tableName,
            List<ColumnSpec> columns, List<PartitionDefinition> partitions, long offset)
            throws IOException {
        PartitionDefinition partition = new PartitionDefinition();
        partition.offset = offset;
        partition.rowCount = rows.size();
        output.writeInt(rows.size());
        long bytesWritten = offset + Integer.BYTES;

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Object[] row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                bytesWritten += writeValue(output, columns.get(columnIndex).type(), row[columnIndex]);
            }
        }

        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            ColumnSpec column = columns.get(columnIndex);
            List<Object> values = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                values.add(row[columnIndex]);
            }
            MinMax minMax = minMax(column.type(), values);
            Object min = minMax.min();
            Object max = minMax.max();
            partition.columns.put(column.name(), new ColumnStats(
                    objectMapper.valueToTree(min), objectMapper.valueToTree(max)));
                LOGGER.debug("table={} partition={} column={} min={} max={}",
                    tableName, partitions.size(), column.name(), min, max);
        }
        partitions.add(partition);
        return bytesWritten;
    }

    static long writeValue(DataOutputStream output, ColumnType type, Object value) throws IOException {
        return switch (type) {
            case STRING -> {
                byte[] bytes = ((String) value).getBytes(StandardCharsets.US_ASCII);
                output.writeInt(bytes.length);
                output.write(bytes);
                yield Integer.BYTES + bytes.length;
            }
            case LONG -> {
                output.writeLong((Long) value);
                yield Long.BYTES;
            }
            case DOUBLE -> {
                output.writeDouble((Double) value);
                yield Double.BYTES;
            }
        };
    }

    int compareValues(ColumnType type, Object left, Object right) {
        return switch (type) {
            case STRING -> ((String) left).compareTo((String) right);
            case LONG -> Long.compare((Long) left, (Long) right);
            case DOUBLE -> Double.compare((Double) left, (Double) right);
        };
    }

    private IllegalArgumentException parseError(String csvFilePath, int lineNumber, String detail) {
        return new IllegalArgumentException(csvFilePath + ": line " + lineNumber + ": " + detail);
    }

    record MinMax(Object min, Object max) {
    }

    MinMax minMax(ColumnType type, List<Object> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute min/max of an empty list");
        }
        Object min = values.get(0);
        Object max = min;
        for (Object value : values) {
            if (compareValues(type, value, min) < 0) {
                min = value;
            }
            if (compareValues(type, value, max) > 0) {
                max = value;
            }
        }
        return new MinMax(min, max);
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void moveDataFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {
    }

    public ScanStats getLastScanStats() {
        return lastScanStats;
    }
    
    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) { 
        long startNanos = System.nanoTime();
        TableDefinition table = catalogFile.tables.get(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        int columnIndex = findColumnIndex(table.columns, columnName);
        if (columnIndex < 0) {
            throw new IllegalArgumentException("Unknown column: " + columnName);
        }
        if (comparison == null) {
            throw new IllegalArgumentException("Comparison must not be null");
        }
        ColumnType columnType = table.columns.get(columnIndex).type();
        validateConstant(columnType, constant);

        int partitionsRead = 0;
        int partitionsPruned = 0;
        List<Object[]> result = new ArrayList<>();
        if (table.dataFile != null) {
            Path dataFile = dataDirectory.resolve(table.dataFile);
            try (RandomAccessFile input = new RandomAccessFile(dataFile.toFile(), "r")) {
                validateFileHeader(input, dataFile);
                for (int partitionIndex = 0; partitionIndex < table.partitions.size(); partitionIndex++) {
                    PartitionDefinition partition = table.partitions.get(partitionIndex);
                    ColumnStats stats = partition.columns.get(columnName);
                    if (stats == null) {
                        throw new IllegalStateException("Missing statistics for column " + columnName);
                    }
                    Object min = jsonValue(stats.min, columnType);
                    Object max = jsonValue(stats.max, columnType);
                    boolean pruned = cannotMatch(columnType, comparison, constant, min, max);
                    LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                            logValue(tableName), logValue(columnName), comparison, logValue(constant),
                            partitionIndex, logValue(min), logValue(max), pruned ? "PRUNED" : "READ");
                    if (pruned) {
                        partitionsPruned++;
                    } else {
                        partitionsRead++;
                        readPartition(input, partition, table.columns, columnIndex, columnType,
                                comparison, constant, result);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read data file " + dataFile, exception);
            }
        }

        lastScanStats = new ScanStats(table.partitions.size(), partitionsRead, partitionsPruned);
        LOGGER.debug("table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                logValue(tableName), logValue(columnName), comparison, logValue(constant),
                partitionsRead, partitionsPruned, result.size(),
                (System.nanoTime() - startNanos) / 1_000_000);
        return result;
    }

    public int findColumnIndex(List<ColumnSpec> columns, String columnName) {
        for (int index = 0; index < columns.size(); index++) {
            if (Objects.equals(columns.get(index).name(), columnName)) {
                return index;
            }
        }
        return -1;
    }

    public void validateConstant(ColumnType type, Object constant) {
        boolean valid = switch (type) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
        if (!valid) {
            throw new IllegalArgumentException("Constant type does not match column type " + type);
        }
    }

    boolean cannotMatch(ColumnType type, Comparison comparison, Object constant,
            Object min, Object max) {
        return switch (comparison) {
            case EQUALS -> compareValues(type, constant, min) < 0
                    || compareValues(type, constant, max) > 0;
            case LESS_THAN -> compareValues(type, min, constant) >= 0;
            case GREATER_THAN -> compareValues(type, max, constant) <= 0;
        };
    }

    public void readPartition(RandomAccessFile input, PartitionDefinition partition,
            List<ColumnSpec> columns, int predicateColumnIndex, ColumnType predicateType,
            Comparison comparison, Object constant, List<Object[]> result) throws IOException {
        input.seek(partition.offset);
        int rowCount = input.readInt();
        if (rowCount != partition.rowCount) {
            throw new IllegalStateException("Partition row count does not match catalog");
        }
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            Object[] row = new Object[columns.size()];
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                row[columnIndex] = readValue(input, columns.get(columnIndex).type());
            }
            if (matches(predicateType, comparison, row[predicateColumnIndex], constant)) {
                result.add(row);
            }
        }
    }

    static Object readValue(RandomAccessFile input, ColumnType type) throws IOException {
        return switch (type) {
            case STRING -> {
                int length = input.readInt();
                if (length < 0) {
                    throw new IllegalStateException("Negative string length in data file");
                }
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                yield new String(bytes, StandardCharsets.US_ASCII);
            }
            case LONG -> input.readLong();
            case DOUBLE -> input.readDouble();
        };
    }

    private boolean matches(ColumnType type, Comparison comparison, Object value, Object constant) {
        int comparisonResult = compareValues(type, value, constant);
        return switch (comparison) {
            case EQUALS -> comparisonResult == 0;
            case LESS_THAN -> comparisonResult < 0;
            case GREATER_THAN -> comparisonResult > 0;
        };
    }

    private Object jsonValue(JsonNode value, ColumnType type) {
        return switch (type) {
            case STRING -> value.textValue();
            case LONG -> value.longValue();
            case DOUBLE -> value.doubleValue();
        };
    }

    private void validateFileHeader(RandomAccessFile input, Path dataFile) throws IOException {
        if (input.readInt() != FILE_MAGIC || input.readInt() != FILE_VERSION) {
            throw new IllegalStateException("Unsupported data file format: " + dataFile);
        }
    }

    private String logValue(Object value) {
        return String.valueOf(value).replace(',', '_');
    }
}
