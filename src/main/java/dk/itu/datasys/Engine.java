package dk.itu.datasys;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.Specifications.Comparison;

import dk.itu.datasys.SqlParser;

public final class Engine {

    public static void main(String[] args) throws URISyntaxException, IOException {
        SqlPrinter sqlPrinter = new SqlPrinter();
        SqlParser sqlParser = new SqlParser();

        Path sqlSubsetPath = Paths.get(
            "src", "test", "resources", "sql_subset_w3.csv");

        String sql = Files.readString(sqlSubsetPath, StandardCharsets.UTF_8);
        
        List<Statement> statements = sqlParser.parse(sql);

        for (Statement statement : statements) {
            System.out.println(sqlPrinter.print(statement));
        }
    }
}