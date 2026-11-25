package dk.itu.datasys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Specifications.ColumnSpec;

public final class Catalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(Catalog.class);

    @JsonProperty("tables")
    public Map<String, TableVersions> tables = new LinkedHashMap<>();

    public Catalog() {
    }

    void normalize() {
        tables.forEach((tableName, versions) -> versions.normalize(tableName));
    }

    public static final class TableVersions {
        @JsonProperty("name")
        private String name;

        @JsonProperty("columns")
        private List<ColumnSpec> columns;

        @JsonProperty("dataFile")
        private String dataFile;

        @JsonProperty("partitionsByVersion")
        private Map<Integer, List<Partition>> partitionsByVersion = new TreeMap<>();

        @JsonProperty(value = "version", access = Access.WRITE_ONLY)
        private Integer legacyVersion;

        @JsonProperty(value = "partitions", access = Access.WRITE_ONLY)
        private List<Partition> legacyPartitions;

        public TableVersions() {
        }

        TableVersions(String name, List<ColumnSpec> columns) {
            this.name = name;
            this.columns = List.copyOf(columns);
            this.partitionsByVersion.put(0, List.of());
        }

        private void normalize(String tableName) {
            if (name == null) {
                name = tableName;
            }
            columns = columns == null ? List.of() : List.copyOf(columns);
            if (partitionsByVersion == null) {
                partitionsByVersion = new TreeMap<>();
            }
            if (partitionsByVersion.isEmpty()) {
                int version = legacyVersion == null ? 0 : legacyVersion;
                List<Partition> partitions = legacyPartitions == null
                        ? List.of() : List.copyOf(legacyPartitions);
                partitionsByVersion.put(version, partitions);
            } else {
                partitionsByVersion.replaceAll((version, partitions) -> List.copyOf(partitions));
            }
            legacyVersion = null;
            legacyPartitions = null;
        }

        public String getName() {
            return name;
        }

        public List<ColumnSpec> getColumns() {
            return columns;
        }

        public String getDataFile() {
            return dataFile;
        }

        public Map<Integer, List<Partition>> getPartitionsByVersion() {
            return Collections.unmodifiableMap(partitionsByVersion);
        }

        public synchronized int latestVersion() {
            return partitionsByVersion.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        public synchronized Snapshot snapshot() {
            int version = latestVersion();
            return new Snapshot(name, version, partitionsByVersion.get(version));
        }

        void setDataFile(String dataFile) {
            this.dataFile = dataFile;
        }

        synchronized int commit(int base, List<Partition> partitions) {
            int version = base + 1;
            int current = latestVersion();
            List<Partition> committedPartitions =
                    Collections.unmodifiableList(new ArrayList<>(partitions));
            if (current != base
                || partitionsByVersion.putIfAbsent(version, committedPartitions) != null) {
            current = latestVersion();
                LOGGER.error("conflict table={} base={} current={}", name, base, current);
                throw new ConflictException(name, base, current);
            }
            return version;
        }
    }

    public record Snapshot(String name, int version, List<Partition> partitions) {
    }

    public static final class ConflictException extends IllegalStateException {
        public ConflictException(String tableName, int base, int current) {
            super("Conflict committing table " + tableName + ": base version " + base
                    + ", current version " + current);
        }
    }

    public static final class Partition {
        @JsonProperty("offset")
        public final long offset;

        @JsonProperty("rowCount")
        public final int rowCount;

        @JsonProperty("columns")
        public final Map<String, ColumnStats> columns;

        @JsonCreator
        public Partition(@JsonProperty("offset") long offset,
                @JsonProperty("rowCount") int rowCount,
                @JsonProperty("columns") Map<String, ColumnStats> columns) {
            this.offset = offset;
            this.rowCount = rowCount;
            this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns == null
                    ? Map.of() : columns));
        }
    }

    public static final class ColumnStats {
        @JsonProperty("min")
        public final JsonNode min;

        @JsonProperty("max")
        public final JsonNode max;

        @JsonCreator
        public ColumnStats(@JsonProperty("min") JsonNode min,
                @JsonProperty("max") JsonNode max) {
            this.min = min;
            this.max = max;
        }
    }
}
