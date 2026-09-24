package dk.itu.datasys;

import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.sql.SqlLexer;

public final class SqlParser {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(SqlParser.class);

    public List<Statement> parse(String sqlText) {
        long start = System.nanoTime(); //Timer for logging

        try {
            SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
            ThrowingErrorListener errorListener = new ThrowingErrorListener();

            lexer.removeErrorListeners();
            lexer.addErrorListener(errorListener);

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            dk.itu.datasys.sql.SqlParser generatedParser =
                    new dk.itu.datasys.sql.SqlParser(tokens);

            generatedParser.removeErrorListeners();
            generatedParser.addErrorListener(errorListener);

            List<Statement> statements =
                    new SqlAstBuilder().visitScript(generatedParser.script());

            LOGGER.debug("statements={} durationMs={}",
                    statements.size(), durationMs(start));

            return statements;
        } catch (SqlParseException exception) {
            LOGGER.error("failed line={} col={} durationMs={}",
                    exception.line(), exception.column(), durationMs(start));
            throw exception;
        }
    }

    private static long durationMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer,
                Object offendingSymbol, int line, int charPositionInLine,
                String message, org.antlr.v4.runtime.RecognitionException exception) {
            throw new SqlParseException(message, line, charPositionInLine);
        }
    }
}