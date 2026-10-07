package dk.itu.datasys;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Engine {

    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) {
        LOGGER.debug("engine started");

        if (args.length == 0) {
            System.out.println(teamName());
            System.out.println("Usage: \"SQL statement\" | -f <script.sql>");
            return;
        }

        try {
            String sql;
            if (args.length == 1) {
                sql = args[0];
            } else if (args.length == 2 && args[0].equals("-f")) {
                sql = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
            } else {
                throw new IllegalArgumentException(
                        "Expected one SQL statement or -f followed by a SQL script path");
            }

            StatementExecutor executor = new StatementExecutor(new StorageEngine(Path.of("data")));
            for (StatementExecutor.ExecutionResult result : executor.execute(sql)) {
                if (result.statement() instanceof Statement.SelectStatement) {
                    for (Object[] row : result.rows()) {
                        printCsvRow(row);
                    }
                }
            }
        } catch (Exception exception) {
            String message = exception.getMessage();
            System.err.println("Error: " + (message == null ? exception.getClass().getSimpleName() : message));
            System.exit(1);
        } finally{
            LOGGER.debug("engine stopped");
        }
    }

    private static void printCsvRow(Object[] row) {
        for (int index = 0; index < row.length; index++) {
            if (index > 0) {
                System.out.print(',');
            }
            String value = String.valueOf(row[index]);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                System.out.print('"');
                System.out.print(value.replace("\"", "\"\""));
                System.out.print('"');
            } else {
                System.out.print(value);
            }
        }
        System.out.println();
    }

    static String teamName(){
        return "Team 2";
    }
}