package dk.itu.datasys;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import dk.itu.datasys.Specifications.*;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.Statement.SelectStatement;

public class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final int FILE_MAGIC = 0x44534231;
    private static final int FILE_VERSION = 1;
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 1000;

    private final Path dataDirectory;
    private final Path catalogPath;
    private volatile Catalog catalogFile;
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

    public void createTable(String tableName, List<ColumnSpec> columns) throws IllegalArgumentException {
        long startNanos = System.nanoTime();
        synchronized (this) {
            try {
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

                Catalog candidate = copyCatalog(catalogFile);
                candidate.tables.put(tableName, new TableDefinition(columns));
                persistCatalog(candidate);
                catalogFile = candidate;
                String durationMs = LogDuration.formatMillisSince(startNanos);
                LOGGER.debug("statement=CREATE table={} rows=0 partitions=0 version={} durationMs={}",
                        tableName, candidate.tables.get(tableName).version, durationMs);
            } catch (RuntimeException exception) {
                String durationMs = LogDuration.formatMillisSince(startNanos);
                LOGGER.error("createTable failed table={} failureType={} durationMs={}",
                        tableName, exception.getClass().getSimpleName(), durationMs);
                throw exception;
            }
        }
    }

    /** The table's schema, in column order. */
    public List<ColumnSpec> schema(String tableName) {
        TableDefinition table = snapshotTable(tableName);
        return table == null ? null : List.copyOf(table.columns);
    }

    synchronized TableDefinition snapshotTable(String tableName) {
        TableDefinition table = catalogFile.tables.get(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return immutableTableCopy(table);
    }

    public TableDefinition tableDefinition(String tableName) {
        return snapshotTable(tableName);
    }

    public long tableVersion(String tableName) {
        return snapshotTable(tableName).version;
    }

    void recordScanStats(int partitionsTotal, int partitionsRead) {
        lastScanStats = new ScanStats(partitionsTotal, partitionsRead,
                partitionsTotal - partitionsRead);
    }

    private void persistCatalog(Catalog catalog) {
        Path temporaryCatalog = catalogPath.resolveSibling("catalog.json.tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryCatalog.toFile(), catalog);
            Files.move(temporaryCatalog, catalogPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IllegalStateException("Atomic catalog rename is unavailable: " + catalogPath,
                    exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not atomically persist catalog " + catalogPath,
                    exception);
        } finally {
            deleteIfExists(temporaryCatalog);
        }
    }

    private Catalog copyCatalog(Catalog source) {
        Catalog copy = new Catalog();
        for (Map.Entry<String, TableDefinition> entry : source.tables.entrySet()) {
            copy.tables.put(entry.getKey(), immutableTableCopy(entry.getValue()));
        }
        return copy;
    }

    private TableDefinition immutableTableCopy(TableDefinition source) {
        TableDefinition copy = new TableDefinition();
        copy.version = source.version;
        copy.columns = source.columns == null ? null : new ArrayList<>(source.columns);
        copy.dataFile = source.dataFile;
        copy.partitions = new ArrayList<>();
        if (source.partitions != null) {
            for (PartitionDefinition partition : source.partitions) {
                PartitionDefinition partitionCopy = new PartitionDefinition();
                partitionCopy.offset = partition.offset;
                partitionCopy.rowCount = partition.rowCount;
                for (Map.Entry<String, ColumnStats> entry : partition.columns.entrySet()) {
                    partitionCopy.columns.put(entry.getKey(),
                            new ColumnStats(entry.getValue().min, entry.getValue().max));
                }
                copy.partitions.add(partitionCopy);
            }
        }
        return copy;
    }
    
    public void copyFile(String tableName, String csvFilePath) {
        long startNanos = System.nanoTime();
        synchronized (this) {
            TableDefinition table = catalogFile.tables.get(tableName);
            if (table == null) {
                throw new IllegalArgumentException("Unknown table: " + tableName);
            }

            Path source = Path.of(csvFilePath);
            Path dataDirectoryPath = dataDirectory.resolve("data");
            Path dataFile = dataDirectoryPath.resolve(tableName + ".bin");
            List<PartitionDefinition> appendedPartitions = new ArrayList<>();
            int rowCount = 0;

            try {
                Files.createDirectories(dataDirectoryPath);
                boolean createHeader = !Files.exists(dataFile) || Files.size(dataFile) == 0;
                try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.US_ASCII);
                        DataOutputStream output = new DataOutputStream(
                                new BufferedOutputStream(Files.newOutputStream(dataFile,
                                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                                        StandardOpenOption.APPEND)))) {
                    if (createHeader) {
                        output.writeInt(FILE_MAGIC);
                        output.writeInt(FILE_VERSION);
                    }
                    long bytesWritten = createHeader ? 8L : Files.size(dataFile);
                    List<Object[]> rows = new ArrayList<>(maxRowsPerPartition);
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        Object[] row = parseCsvLine(line, table.columns, csvFilePath, lineNumber);
                        rows.add(row);
                        if (rows.size() == maxRowsPerPartition) {
                            bytesWritten = writePartition(output, rows, tableName, table.columns,
                                    appendedPartitions, bytesWritten);
                            rowCount += rows.size();
                            rows.clear();
                        }
                    }
                    if (!rows.isEmpty()) {
                        bytesWritten = writePartition(output, rows, tableName, table.columns,
                                appendedPartitions, bytesWritten);
                        rowCount += rows.size();
                    }
                }

                if (rowCount == 0) {
                    LOGGER.debug("statement=COPY table={} rows=0 partitions=0 version={} durationMs={}",
                            tableName, table.version, LogDuration.formatMillisSince(startNanos));
                    return;
                }

                Catalog candidate = copyCatalog(catalogFile);
                TableDefinition candidateTable = candidate.tables.get(tableName);
                candidateTable.dataFile = dataDirectory.relativize(dataFile).toString();
                candidateTable.partitions.addAll(appendedPartitions);
                candidateTable.version++;
                persistCatalog(candidate);
                catalogFile = candidate;

                LOGGER.debug("statement=COPY table={} rows={} partitions={} version={} durationMs={}",
                        tableName, rowCount, appendedPartitions.size(), candidateTable.version,
                        LogDuration.formatMillisSince(startNanos));
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not copy " + csvFilePath, exception);
            }
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
        if (comparison == null) {
            throw new IllegalArgumentException("Comparison must not be null");
        }
        return new StatementExecutor(this).execute(new Statement.SelectStatement(tableName,
                Optional.of(new Statement.Predicate(columnName, comparison, constant))));
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

    public List<Object[]> readPartitionRows(String tableName, PartitionDefinition partition) {
        return readPartitionRows(snapshotTable(tableName), partition);
    }

    public List<Object[]> readPartitionRows(TableDefinition table, PartitionDefinition partition) {
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + table);
        }
        if (table.dataFile == null) {
            return List.of();
        }

        Path dataFile = dataDirectory.resolve(table.dataFile);
        List<Object[]> rows = new ArrayList<>(partition.rowCount);
        try (RandomAccessFile input = new RandomAccessFile(dataFile.toFile(), "r")) {
            validateFileHeader(input, dataFile);
            input.seek(partition.offset);
            int rowCount = input.readInt();
            if (rowCount != partition.rowCount) {
                throw new IllegalStateException("Partition row count does not match catalog");
            }
            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                Object[] row = new Object[table.columns.size()];
                for (int columnIndex = 0; columnIndex < table.columns.size(); columnIndex++) {
                    row[columnIndex] = readValue(input, table.columns.get(columnIndex).type());
                }
                rows.add(row);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read data file " + dataFile, exception);
        }
        return rows;
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

    Object jsonValue(JsonNode value, ColumnType type) {
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

    String logValue(Object value) {
        return String.valueOf(value).replace(',', '_');
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
        @JsonProperty("version")
        public long version;

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
            this.version = 0;
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

    public void deleteRows(String tableName, Predicate predicate) {
        long startNanos = System.nanoTime();
        synchronized (this) {
            TableDefinition table = snapshotTable(tableName);
            if (table.dataFile == null || table.partitions.isEmpty()) {
                LOGGER.debug("statement=DELETE table={} rowsDeleted=0 partitionsRewritten=0 partitionsCarried={} version={} durationMs={}",
                        tableName, table.partitions.size(), table.version, LogDuration.formatMillisSince(startNanos));
                return;
            }

            Path dataFile = dataDirectory.resolve(table.dataFile);
            List<PartitionDefinition> replacementPartitions = new ArrayList<>();
            int rowsDeleted = 0;
            int partitionsRewritten = 0;
            int partitionsCarried = 0;

            try {
                try (RandomAccessFile input = new RandomAccessFile(dataFile.toFile(), "r");
                        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                                Files.newOutputStream(dataFile, StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)))) {
                    validateFileHeader(input, dataFile);
                    long appendOffset = Files.size(dataFile);

                    for (PartitionDefinition partition : table.partitions) {
                        boolean mayContainMatch = predicate == null;
                        if (predicate != null) {
                            int columnIndex = findColumnIndex(table.columns, predicate.columnName());
                            if (columnIndex < 0) {
                                throw new IllegalArgumentException("Unknown column: " + predicate.columnName());
                            }
                            ColumnType columnType = table.columns.get(columnIndex).type();
                            validateConstant(columnType, predicate.constant());
                            ColumnStats stats = partition.columns.get(predicate.columnName());
                            if (stats == null) {
                                throw new IllegalStateException("Missing statistics for column " + predicate.columnName());
                            }
                            mayContainMatch = !cannotMatch(columnType, predicate.comparison(), predicate.constant(),
                                    jsonValue(stats.min, columnType), jsonValue(stats.max, columnType));
                        }

                        if (!mayContainMatch) {
                            replacementPartitions.add(partition);
                            partitionsCarried++;
                            continue;
                        }

                        List<Object[]> rows = readPartitionRows(table, partition);
                        List<Object[]> retained = new ArrayList<>();
                        int columnIndex = predicate == null ? -1
                                : findColumnIndex(table.columns, predicate.columnName());
                        for (Object[] row : rows) {
                            boolean matchesPredicate = predicate == null;
                            if (predicate != null) {
                                ColumnType columnType = table.columns.get(columnIndex).type();
                                matchesPredicate = matches(columnType, predicate.comparison(),
                                        row[columnIndex], predicate.constant());
                            }
                            if (matchesPredicate) {
                                rowsDeleted++;
                            } else {
                                retained.add(row);
                            }
                        }

                        if (retained.size() == rows.size()) {
                            replacementPartitions.add(partition);
                            partitionsCarried++;
                        } else {
                            partitionsRewritten++;
                            for (int start = 0; start < retained.size(); start += maxRowsPerPartition) {
                                int end = Math.min(start + maxRowsPerPartition, retained.size());
                                appendOffset = writePartition(output, retained.subList(start, end), tableName,
                                        table.columns, replacementPartitions, appendOffset);
                            }
                        }
                    }
                }

                if (rowsDeleted == 0) {
                    LOGGER.debug("statement=DELETE table={} rowsDeleted=0 partitionsRewritten=0 partitionsCarried={} version={} durationMs={}",
                            tableName, table.partitions.size(), table.version, LogDuration.formatMillisSince(startNanos));
                    return;
                }

                Catalog candidate = copyCatalog(catalogFile);
                TableDefinition candidateTable = candidate.tables.get(tableName);
                candidateTable.partitions = replacementPartitions;
                candidateTable.version++;
                persistCatalog(candidate);
                catalogFile = candidate;

                LOGGER.debug("statement=DELETE table={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} version={} durationMs={}",
                        tableName, rowsDeleted, partitionsRewritten, partitionsCarried,
                        candidateTable.version, LogDuration.formatMillisSince(startNanos));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not delete rows from " + tableName, exception);
            }
        }
    }
}
