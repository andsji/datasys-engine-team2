package dk.itu.datasys;

import static dk.itu.datasys.Specifications.ColumnType.DOUBLE;
import static dk.itu.datasys.Specifications.ColumnType.LONG;
import static dk.itu.datasys.Specifications.ColumnType.STRING;
import static dk.itu.datasys.Specifications.Comparison.EQUALS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Statement.CopyStatement;
import dk.itu.datasys.Statement.CreateTableStatement;
import dk.itu.datasys.Statement.Predicate;
import dk.itu.datasys.Statement.SelectStatement;

class BinderTests {
        private static final List<ColumnSpec> SCHEMA = List.of(
                new ColumnSpec("city", STRING), new ColumnSpec("distance", LONG),
                new ColumnSpec("price", DOUBLE));

        @TempDir
        Path dataDirectory;

        @Test
        void validStatementsBind() {
                StorageEngine engine = new StorageEngine(dataDirectory);
                engine.createTable("trips", SCHEMA);
                Binder binder = new Binder(engine);

                assertDoesNotThrow(() -> binder.bind(
                        new SelectStatement("trips", Optional.empty())));
                assertDoesNotThrow(() -> binder.bind(
                        new SelectStatement("trips", Optional.of(
                                new Predicate("distance", EQUALS, 12L)))));
                assertDoesNotThrow(() -> binder.bind(
                        new CopyStatement("trips", "trips.csv")));
        }

        @Test
        void invalidStatementsDoNotBind() {
                StorageEngine engine = new StorageEngine(dataDirectory);
                engine.createTable("trips", SCHEMA);
                Binder binder = new Binder(engine);

                assertThrows(IllegalArgumentException.class, () ->
                        binder.bind(new SelectStatement("missing", Optional.empty())));

                assertThrows(IllegalArgumentException.class, () ->
                        binder.bind(new SelectStatement("trips", Optional.of(
                                new Predicate("unknown", EQUALS, 1L)))));

                assertThrows(IllegalArgumentException.class, () ->
                        binder.bind(new SelectStatement("trips", Optional.of(
                                new Predicate("distance", EQUALS, "x")))));

                assertThrows(IllegalArgumentException.class, () ->
                        binder.bind(new CreateTableStatement("other", List.of(
                                new ColumnSpec("city", STRING),
                                new ColumnSpec("city", LONG)))));
        }
}