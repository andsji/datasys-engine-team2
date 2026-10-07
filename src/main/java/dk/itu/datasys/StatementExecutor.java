package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.SelectStatement;
import dk.itu.datasys.operators.Operator;

public final class StatementExecutor {
    private final StorageEngine storageEngine;
    private final Binder binder;
    private final Planner planner;
    private final SqlParser parser;
    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);

    public StatementExecutor(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
        this.binder = new Binder(storageEngine);
        this.planner = new Planner(storageEngine);
        this.parser = new SqlParser();
    }

    public List<ExecutionResult> execute(String sqlText) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        
        try{
        int statementNumber = 0;
        List<ExecutionResult> results = new ArrayList<>();
        for (Statement statement : parser.parse(sqlText)) {
            statementNumber++;
            MDC.put("statementNumber", String.valueOf(statementNumber));
            results.add(new ExecutionResult(statement, execute(statement)));
            LOGGER.debug("statementType={}", statement.getClass().getSimpleName());
        }
            return List.copyOf(results);
        } finally {
            MDC.put("statementNumber", "0");
            LOGGER.debug("engine stopped");
            MDC.clear();
        }
        
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