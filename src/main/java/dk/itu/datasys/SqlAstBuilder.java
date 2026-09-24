package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.Specifications.Comparison;
import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.Statement.SelectStatement;
import dk.itu.datasys.sql.SqlBaseVisitor;

public final class SqlAstBuilder extends SqlBaseVisitor<Object> {
    @Override
    public List<Statement> visitScript(dk.itu.datasys.sql.SqlParser.ScriptContext context) {
        return context.statement().stream()
                .map(statement -> (Statement) visit(statement))
                .toList();
    }

    @Override
    public Statement visitStatement(dk.itu.datasys.sql.SqlParser.StatementContext context) {
        return (Statement) visit(context.getChild(0));
    }

    @Override
    public CreateTableStatement visitCreateTable(
            dk.itu.datasys.sql.SqlParser.CreateTableContext context) {
        List<ColumnSpec> columns = context.columnDef().stream()
                .map(column -> (ColumnSpec) visit(column))
                .toList();
        return new CreateTableStatement(context.IDENTIFIER().getText(), columns);
    }

    @Override
    public ColumnSpec visitColumnDef(dk.itu.datasys.sql.SqlParser.ColumnDefContext context) {
        return new ColumnSpec(context.IDENTIFIER().getText(),
                (ColumnType) visit(context.columnType()));
    }

    @Override
    public ColumnType visitColumnType(
            dk.itu.datasys.sql.SqlParser.ColumnTypeContext context) {
        return switch (context.getText().toUpperCase()) {
            case "STRING" -> ColumnType.STRING;
            case "LONG" -> ColumnType.LONG;
            case "DOUBLE" -> ColumnType.DOUBLE;
            default -> throw new IllegalStateException("Unexpected column type");
        };
    }

    @Override
    public CopyStatement visitCopy(dk.itu.datasys.sql.SqlParser.CopyContext context) {
        return new CopyStatement(context.IDENTIFIER().getText(), unquote(context.STRING_LITERAL().getText()));
    }

    @Override
    public SelectStatement visitSelect(dk.itu.datasys.sql.SqlParser.SelectContext context) {
        Optional<Predicate> predicate = context.predicate() == null
                ? Optional.empty()
                : Optional.of((Predicate) visit(context.predicate()));
        return new SelectStatement(context.IDENTIFIER().getText(), predicate);
    }

    @Override
    public Predicate visitPredicate(dk.itu.datasys.sql.SqlParser.PredicateContext context) {
        Comparison comparison = switch (context.comparison.getText()) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException("Unexpected comparison");
        };
        return new Predicate(context.IDENTIFIER().getText(), comparison,
                visit(context.literal()));
    }

    @Override
    public Object visitLiteral(dk.itu.datasys.sql.SqlParser.LiteralContext context) {
        String text = context.getText();
        if (context.STRING_LITERAL() != null) {
            return unquote(text);
        }
        if (context.DOUBLE_LITERAL() != null) {
            return Double.valueOf(text);
        }
        return Long.valueOf(text);
    }

    private static String unquote(String text) {
        return text.substring(1, text.length() - 1);
    }
}