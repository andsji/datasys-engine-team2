package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.StorageEngine.ColumnStats;
import dk.itu.datasys.StorageEngine.PartitionDefinition;
import dk.itu.datasys.StorageEngine.TableDefinition;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.Statement.SelectStatement;
import dk.itu.datasys.operators.FilterOperator;
import dk.itu.datasys.operators.Operator;
import dk.itu.datasys.operators.ScanOperator;

public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine storageEngine;

    public Planner(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    public Operator plan(SelectStatement statement) {
        Objects.requireNonNull(statement, "statement");
        TableDefinition table = storageEngine.tableDefinition(statement.tableName());
        List<PartitionDefinition> survivingPartitions = new ArrayList<>();
        Predicate predicate = statement.where().orElse(null);

        if (predicate == null) {
            survivingPartitions.addAll(table.partitions);
        } else {
            int columnIndex = storageEngine.findColumnIndex(table.columns, predicate.columnName());
            if (columnIndex < 0) {
                throw new IllegalArgumentException("Unknown column: " + predicate.columnName());
            }
            if (predicate.comparison() == null) {
                throw new IllegalArgumentException("Comparison must not be null");
            }
            ColumnType columnType = table.columns.get(columnIndex).type();
            storageEngine.validateConstant(columnType, predicate.constant());
            for (int partitionIndex = 0; partitionIndex < table.partitions.size(); partitionIndex++) {
                PartitionDefinition partition = table.partitions.get(partitionIndex);
                ColumnStats stats = partition.columns.get(predicate.columnName());
                if (stats == null) {
                    throw new IllegalStateException(
                            "Missing statistics for column " + predicate.columnName());
                }
                Object min = storageEngine.jsonValue(stats.min, columnType);
                Object max = storageEngine.jsonValue(stats.max, columnType);
                boolean pruned = storageEngine.cannotMatch(columnType, predicate.comparison(),
                        predicate.constant(), min, max);
                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        storageEngine.logValue(statement.tableName()),
                        storageEngine.logValue(predicate.columnName()), predicate.comparison(),
                        storageEngine.logValue(predicate.constant()), partitionIndex,
                        storageEngine.logValue(min), storageEngine.logValue(max),
                        pruned ? "PRUNED" : "READ");
                if (!pruned) {
                    survivingPartitions.add(partition);
                }
            }
        }

        storageEngine.recordScanStats(table.partitions.size(), survivingPartitions.size());
        Operator scan = new ScanOperator(storageEngine, statement.tableName(), survivingPartitions);
        return predicate == null ? scan : new FilterOperator(scan, predicate);
    }
}