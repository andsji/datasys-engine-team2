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
                                "distance", Comparison.GREATER_THAN, 100L))),
                new Statement.DeleteStatement("trips", Optional.empty()),
                new Statement.DeleteStatement("trips", Optional.of(
                        new Statement.Predicate(
                                "city", Comparison.EQUALS, "Odense"))));

        for (Statement statement : statements) {
            assertEquals(statement,
                    parser.parse(printer.print(statement)).get(0));
        }
    }

    @Test
    void deletePrintRoundTripWithAndWithoutWhere() {
        List<Statement> deletes = List.of(
                new Statement.DeleteStatement("trips", Optional.empty()),
                new Statement.DeleteStatement("trips", Optional.of(
                        new Statement.Predicate("city", Comparison.EQUALS, "Odense"))));

        for (Statement delete : deletes) {
            assertEquals(delete, parser.parse(printer.print(delete)).get(0));
        }
    }
}