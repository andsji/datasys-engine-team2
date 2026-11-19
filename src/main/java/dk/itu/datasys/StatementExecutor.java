package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.DeleteStatement;
import dk.itu.datasys.Statement.SelectStatement;
import dk.itu.datasys.operators.Operator;
import dk.itu.datasys.StorageEngine.ScanStats;
import dk.itu.datasys.StorageEngine.TableDefinition;

public final class StatementExecutor {
    private final StorageEngine storageEngine;
    private final Binder binder;
    private final Planner planner;
    private final SqlParser parser;
    private int statementNumber;
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementExecutor.class);

    public StatementExecutor(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
        this.binder = new Binder(storageEngine);
        this.planner = new Planner(storageEngine);
        this.parser = new SqlParser();
    }

    public List<ExecutionResult> execute(String sqlText) {

        try {
            List<ExecutionResult> results = new ArrayList<>();
            for (Statement statement : parser.parse(sqlText)) {
                statementNumber++;
                MDC.put("statementNumber", String.valueOf(statementNumber));
                results.add(new ExecutionResult(statement, execute(statement)));
                //LOGGER.debug("statementType={}", statement.getClass().getSimpleName());
            }
            return List.copyOf(results);
        } finally {

        }

    }

    public List<Object[]> execute(Statement statement) {
        binder.bind(statement);
        switch (statement) {
            case CreateTableStatement createTable ->
                    storageEngine.createTable(createTable.tableName(), createTable.columns());
            case CopyStatement copy ->
                    storageEngine.copyFile(copy.tableName(), copy.csvFilePath());
            case DeleteStatement delete ->
                    storageEngine.deleteRows(delete.tableName(), delete.where().orElse(null));
            case SelectStatement select -> {
                long startNanos = System.nanoTime();
                TableDefinition snapshot = storageEngine.tableDefinition(select.tableName());
                List<Object[]> rows = executePlan(planner.plan(select));
                String durationMs = LogDuration.formatMillisSince(startNanos);
                ScanStats scanStats = storageEngine.getLastScanStats();
                LOGGER.debug("statement=SELECT table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                        select.tableName(), rows.size(), scanStats.partitionsRead(),
                        scanStats.partitionsPruned(), snapshot.version, durationMs);
                return rows;
            }
        }
        return List.of();
    }

    private List<Object[]> executePlan(Operator root) {
        List<Object[]> rows = new ArrayList<>();
        root.open();
        try {
            Object[] row;
            while ((row = root.next()) != null) {
                rows.add(row);
            }
        } finally {
            root.close();
        }
        return rows;
    }

    public record ExecutionResult(Statement statement, List<Object[]> rows) {
        public ExecutionResult{rows=List.copyOf(rows);}
    }
}