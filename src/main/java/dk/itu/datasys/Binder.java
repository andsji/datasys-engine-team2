package dk.itu.datasys;

import java.util.Objects;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.Statement.SelectStatement;

public final class Binder {
    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Validates a statement against the catalog. */
    public void bind(Statement statement) {
        Objects.requireNonNull(statement, "statement");
        switch (statement) {
            case CreateTableStatement createTable -> bindCreateTable(createTable);
            case CopyStatement copy -> requireTable(copy.tableName());
            case SelectStatement select -> bindSelect(select);
        }
    }

    private void bindCreateTable(CreateTableStatement statement) {
        if (statement.columns() == null || statement.columns().isEmpty()) {
            throw new IllegalArgumentException("A table must have at least one column");
        }

        java.util.Set<String> columnNames = new java.util.HashSet<>();
        for (ColumnSpec column : statement.columns()) {
            if (column == null || column.name() == null || column.type() == null
                    || !columnNames.add(column.name())) {
                throw new IllegalArgumentException("Column names must be non-null and unique");
            }
        }
    }

    private void bindSelect(SelectStatement statement) {
        for (ColumnSpec column : engine.schema(statement.tableName())) {
            if (statement.where().isPresent()) {
                Predicate predicate = statement.where().get();
                if (column.name().equals(predicate.columnName())) {
                    validateConstant(predicate, column.type());
                    return;
                }
            }
        }
        if (statement.where().isPresent()) {
            throw new IllegalArgumentException("Unknown column: "
                    + statement.where().get().columnName());
        }
    }

    private void validateConstant(Predicate predicate, ColumnType columnType) {
        Object constant = predicate.constant();
        boolean matches = switch (columnType) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
        if (!matches) {
            throw new IllegalArgumentException("Constant type does not match column: "
                    + predicate.columnName());
        }
    }

    private void requireTable(String tableName) {
        engine.schema(tableName);
    }
}