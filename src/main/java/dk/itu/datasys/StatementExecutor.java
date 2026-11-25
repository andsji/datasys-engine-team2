package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.MDC;

import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.DeleteStatement;
import dk.itu.datasys.Statement.SelectStatement;

public final class StatementExecutor {
    private final StorageEngine storageEngine;
    private final Binder binder;
    private final SqlParser parser;
    private int statementNumber;

    public StatementExecutor(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
        this.binder = new Binder(storageEngine);
        this.parser = new SqlParser();
    }

    public List<ExecutionResult> executeSql(String sqlText) {
        List<ExecutionResult> results = new ArrayList<>();
        for (Statement statement : parser.parse(sqlText)) {
            statementNumber++;
            MDC.put("statementNumber", String.valueOf(statementNumber));
            results.add(new ExecutionResult(statement, executeStatement(statement)));
        }
        return List.copyOf(results);
    }

    public List<Object[]> executeStatement(Statement statement) {
        if (!(statement instanceof SelectStatement)) {
            binder.bind(statement);
        }
        switch (statement) {
            case CreateTableStatement createTable ->
                    storageEngine.createTable(createTable.tableName(), createTable.columns());
            case CopyStatement copy ->
                    storageEngine.copyFile(copy.tableName(), copy.csvFilePath());
            case DeleteStatement delete ->
                    storageEngine.deleteRows(delete.tableName(), delete.where().orElse(null));
            case SelectStatement select -> {
                return storageEngine.select(select);
            }
        }
        return List.of();
    }

    public record ExecutionResult(Statement statement, List<Object[]> rows) {
        public ExecutionResult {
            rows = List.copyOf(rows);
        }
    }
}
