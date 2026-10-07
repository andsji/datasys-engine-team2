package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingIntegrationTests {
    @Test
    void failingStatementWritesErrorLineToEngineLog(@TempDir Path tmp)
            throws IOException, InterruptedException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp", System.getProperty("java.class.path"),
                "dk.itu.datasys.Engine",
                "SELECT FROM;")
                .directory(tmp.toFile())
                .start();

        int exitCode = process.waitFor();
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();

        Path logFile = tmp.resolve("logs").resolve("engine.log");
        assertNotEquals(0, exitCode);
        assertTrue(Files.exists(logFile), "Engine log should be created");

        List<String> logLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertTrue(logLines.stream().anyMatch(line -> {
            String[] fields = line.split(",", 7);
            return fields.length == 7
                    && fields[4].equals("ERROR")
                    && fields[5].equals("SqlParser")
                    && fields[6].startsWith("failed line=");
        }), "A failing statement should leave an ERROR row in engine.log");
    }
}