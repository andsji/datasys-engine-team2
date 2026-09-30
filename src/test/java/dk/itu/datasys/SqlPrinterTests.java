package dk.itu.datasys;

import static dk.itu.datasys.Specifications.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SqlPrinterTests {
    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    @Test
    void parsePrintRoundTrip() {
        List<Statement> statements = List.of(
                new Statement.CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new Statement.CopyStatement("trips", "trips.csv"),
                new Statement.SelectStatement("trips", Optional.empty()),
                new Statement.SelectStatement("trips", Optional.of(
                        new Statement.Predicate(
                                "distance", Comparison.GREATER_THAN, 100L))));

        for (Statement statement : statements) {
            assertEquals(statement,
                    parser.parse(printer.print(statement)).get(0));
        }
    }
}