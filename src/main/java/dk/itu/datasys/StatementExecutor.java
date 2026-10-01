package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.SelectStatement;
import dk.itu.datasys.operators.Operator;

public final class StatementExecutor {
    private final StorageEngine storageEngine;
    private final Binder binder;
    private final Planner planner;
    private final SqlParser parser;

    public StatementExecutor(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
        this.binder = new Binder(storageEngine);
        this.planner = new Planner(storageEngine);
        this.parser = new SqlParser();
    }

    public List<ExecutionResult> execute(String sqlText) {
        List<ExecutionResult> results = new ArrayList<>();
        for (Statement statement : parser.parse(sqlText)) {
            results.add(new ExecutionResult(statement, execute(statement)));
        }
        return List.copyOf(results);
    }

    public List<Object[]> execute(Statement statement) {
        binder.bind(statement);
        switch (statement) {
            case CreateTableStatement createTable ->
                    storageEngine.createTable(createTable.tableName(), createTable.columns());
            case CopyStatement copy ->
                    storageEngine.copyFile(copy.tableName(), copy.csvFilePath());
            case SelectStatement select -> {
                return executePlan(planner.plan(select));
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
        public ExecutionResult {
            rows = List.copyOf(rows);
        }
    }
}