package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrontDoorE2ETest {
    @Test
    void scriptWritesOnlyCsvRowsToStdout(@TempDir Path tmp) throws IOException, InterruptedException {
        Path script = tmp.resolve("query.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE city = 'Odense';
                """.formatted(Path.of("src", "test", "resources", "trips.csv").toAbsolutePath().normalize()));

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp", System.getProperty("java.class.path"),
                "dk.itu.datasys.Engine",
                "-f", script.toString())
                .directory(tmp.toFile())
                .start();

        int exitCode = process.waitFor();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, exitCode);
        assertEquals("Odense,95,120.75" + System.lineSeparator(), stdout);
    }

    @Test
    void failingScriptLeavesStdoutEmptyAndReportsOnStderr(@TempDir Path tmp)
            throws IOException, InterruptedException {
        Path script = tmp.resolve("failing.sql");
        Files.writeString(script, "SELECT * FROM missing;");

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp", System.getProperty("java.class.path"),
                "dk.itu.datasys.Engine",
                "-f", script.toString())
                .directory(tmp.toFile())
                .start();

        int exitCode = process.waitFor();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertNotEquals(0, exitCode);
        assertEquals("", stdout);
        assertTrue(stderr.contains("Error:"));
    }

    @Test
    void createTwoCopiesDeleteAndSelectThroughScript(@TempDir Path tmp)
            throws IOException, InterruptedException {
        Path first = tmp.resolve("first.csv");
        Path second = tmp.resolve("second.csv");
        Files.writeString(first, "A,10,1.0\nB,20,2.0\n");
        Files.writeString(second, "C,30,3.0\nD,40,4.0\n");

        Path script = tmp.resolve("versioned.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                COPY trips FROM '%s';
                DELETE FROM trips WHERE city = 'B';
                SELECT * FROM trips;
                """.formatted(first.toAbsolutePath().normalize(),
                        second.toAbsolutePath().normalize()));

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaExecutable,
                "-cp", System.getProperty("java.class.path"),
                "-Ddatasys.dataDirectory=" + tmp.resolve("data"),
                "-Ddatasys.maxRowsPerPartition=2",
                "dk.itu.datasys.Engine", "-f", script.toString())
                .directory(tmp.toFile())
                .start();

        int exitCode = process.waitFor();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertEquals("A,10,1.0" + System.lineSeparator()
                + "C,30,3.0" + System.lineSeparator()
                + "D,40,4.0" + System.lineSeparator(), stdout);
    }
}
