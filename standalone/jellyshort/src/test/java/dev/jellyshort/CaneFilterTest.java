package dev.jellyshort;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CaneFilterTest {
    @Test void keepsTwoActualCaneBlocksDespiteOffsetArmorStandAnchor() {
        var selected = CaneFilter.select(72, 2, false, y -> y >= 74 && y <= 83);
        assertEquals(Set.of(76, 77, 78, 79, 80, 81, 82, 83), selected);
    }
    @Test void hideIncludesBaseAndStopsAtGap() {
        assertEquals(Set.of(74, 75, 76), CaneFilter.select(72, 2, true,
            y -> (y >= 74 && y <= 76) || y == 80));
    }
    @Test void absentOrShortCaneDoesNotHideAnythingInShortenMode() {
        assertTrue(CaneFilter.select(72, 2, false, y -> false).isEmpty());
        assertTrue(CaneFilter.select(72, 2, false, y -> y == 74 || y == 75).isEmpty());
    }
}
