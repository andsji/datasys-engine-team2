package dk.itu.datasys;

import static dk.itu.datasys.Specifications.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SqlParserTests {
    private final SqlParser parser = new SqlParser();

    @Test
    void parsesAllStatementShapes() {
        assertEquals(new Statement.CreateTableStatement("trips", List.of(
                new Specifications.ColumnSpec("city", ColumnType.STRING),
                new Specifications.ColumnSpec("distance", ColumnType.LONG),
                new Specifications.ColumnSpec("price", ColumnType.DOUBLE))),
                parser.parse("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);").get(0));

        assertEquals(new Statement.CopyStatement("trips", "trips.csv"),
                parser.parse("COPY trips FROM 'trips.csv';").get(0));

        assertEquals(new Statement.SelectStatement("trips", Optional.empty()),
                parser.parse("SELECT * FROM trips;").get(0));

        assertEquals(new Statement.SelectStatement("trips", Optional.of(
                new Statement.Predicate("distance", Comparison.GREATER_THAN, 100L))),
                parser.parse("SELECT * FROM trips WHERE distance > 100;").get(0));
    }

    @Test
    void parsesLiteralTypes() {
        var statements = parser.parse(
                "SELECT * FROM t WHERE a = 12;"
                + "SELECT * FROM t WHERE a = 12.0;"
                + "SELECT * FROM t WHERE a = '12';"
                + "SELECT * FROM t WHERE a = -1;"
                + "SELECT * FROM t WHERE a = -1.5;");

        assertInstanceOf(Long.class, ((Statement.SelectStatement) statements.get(0))
                .where().orElseThrow().constant());
        assertInstanceOf(Double.class, ((Statement.SelectStatement) statements.get(1))
                .where().orElseThrow().constant());
        assertInstanceOf(String.class, ((Statement.SelectStatement) statements.get(2))
                .where().orElseThrow().constant());
        assertEquals(-1L, ((Statement.SelectStatement) statements.get(3))
                .where().orElseThrow().constant());
        assertEquals(-1.5, ((Statement.SelectStatement) statements.get(4))
                .where().orElseThrow().constant());
    }

    @Test
    void keywordsAreCaseInsensitiveIdentifiersArePreserved() {
        var statement = (Statement.SelectStatement)
                parser.parse("select * from Trips;").get(0);

        assertEquals("Trips", statement.tableName());
    }

    @Test
    void commentsAndWhitespaceAreSkipped() {
        assertEquals(1, parser.parse(
                "  -- comment\n SELECT * FROM trips; /* comment */").size());
    }

    @Test
    void malformedSqlReportsPosition() {
        for (String sql : List.of(
                "SELECT * FROM trips",
                "CREATE TABLE trips (city STRING;",
                "CREATE TABLE trips (city TEXT);",
                "SELECT * FROM trips WHERE city = 'x;",
                "SELECT * trips;")) {
            SqlParseException exception = assertThrows(
                    SqlParseException.class, () -> parser.parse(sql));

            assertTrue(exception.line() >= 1);
            assertTrue(exception.column() >= 0);
        }
    }
}