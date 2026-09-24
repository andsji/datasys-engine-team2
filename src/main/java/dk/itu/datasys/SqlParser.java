package dk.itu.datasys;

import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import dk.itu.datasys.sql.SqlLexer;

public final class SqlParser {
    public List<Statement> parse(String sqlText) {
        SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
        ThrowingErrorListener errorListener = new ThrowingErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        dk.itu.datasys.sql.SqlParser generatedParser =
            new dk.itu.datasys.sql.SqlParser(tokens);
        generatedParser.removeErrorListeners();
        generatedParser.addErrorListener(errorListener);
        return new SqlAstBuilder().visitScript(generatedParser.script());
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