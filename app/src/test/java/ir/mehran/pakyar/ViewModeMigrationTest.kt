package ir.mehran.pakyar

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewModeMigrationTest {
    @Test fun oldAutomaticDefaultBecomesLargeGrid() {
        assertEquals("GRID_LARGE", ViewModeMigration.resolve(null, null))
        assertEquals("GRID_LARGE", ViewModeMigration.resolve("LIST", false))
    }
    @Test fun manualListChoiceSurvivesMigrationAndRestarts() {
        assertEquals("LIST", ViewModeMigration.resolve("LIST", true))
        // Previous release only persisted this key on an explicit menu selection.
        assertEquals("LIST", ViewModeMigration.resolve("LIST", null))
        assertEquals("LIST", ViewModeMigration.resolve(ViewModeMigration.resolve("LIST", true), true))
    }
    @Test fun legacyGridNamesMigrateWithoutChangingTheChoice() {
        assertEquals("GRID_LARGE", ViewModeMigration.resolve("LARGE", null))
        assertEquals("GRID_MEDIUM", ViewModeMigration.resolve("MEDIUM", null))
        assertEquals("GRID_COMPACT", ViewModeMigration.resolve("COMPACT", null))
    }
    @Test fun newExplicitChoiceIsNotReset() {
        assertEquals("GRID_COMPACT", ViewModeMigration.resolve("GRID_COMPACT", true))
        assertEquals("GRID_LARGE", ViewModeMigration.resolve("invalid", null))
    }
}
