package dk.itu.datasys;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.Specifications.Comparison;
import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.SelectStatement;

public class SqlPrinter {
    /** Renders a statement back to SQL text that parses to an equal statement. */
    public String print(Statement s) { 
        return switch (s) {
            case CreateTableStatement create -> printCreateTable(create);
            case CopyStatement copy -> printCopy(copy);
            case SelectStatement select -> printSelect(select);
        };
    }

    private static String printCreateTable(Statement.CreateTableStatement createTableStatement){
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE " + createTableStatement.tableName() + " (");

        for (int i = 0; i < createTableStatement.columns().size(); i++) {
            ColumnSpec cs = createTableStatement.columns().get(i);
            if(i > 0){
                sb.append(", ");
            }
            sb.append(cs.name()).append(" ").append(columnTypeString(cs.type()));
        }
        sb.append(");");
        return sb.toString();
    }

    private static String printCopy(Statement.CopyStatement copyStatement){
        return "COPY " + copyStatement.tableName() + " FROM '" + escapeSqlString(copyStatement.csvFilePath()) + "';";
    }

    private static String printSelect(Statement.SelectStatement selectStatement){
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(selectStatement.tableName());

        if(selectStatement.where().isPresent()) {
            Statement.Predicate predicate = selectStatement.where().get();
            sb.append(" WHERE ")
            .append(predicate.columnName())
            .append(" ")
            .append(comparisonString(predicate.comparison()))
            .append(" ")
            .append(literalSql(predicate.constant()));
        }

        sb.append(";");
        return sb.toString();
    }

    private static String comparisonString(Comparison comparison){
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }
    

    private static String columnTypeString(ColumnType type){
        return switch (type) {
            case STRING -> "STRING";
            case LONG -> "LONG";
            case DOUBLE -> "DOUBLE";
        };
    }

    private static String escapeSqlString(String value) {
        return value.replace("'", "''");
    }

    private static String literalSql(Object value) {
        if (value instanceof String s) {
            return "'" + escapeSqlString(s) + "'";
        }
        if (value instanceof Long l) {
            return Long.toString(l);
        }
        if (value instanceof Double d) {
            return Double.toString(d);
        }

        throw new IllegalArgumentException("invalid type: " + value);
    }
}