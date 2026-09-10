package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void teamName() {
        assertEquals("team2", new Engine().teamName());
    }

    @Test
    void teamNameFails() {
        assertEquals("Team team2", new Engine().teamName());
    }
}