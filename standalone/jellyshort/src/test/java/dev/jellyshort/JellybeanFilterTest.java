package dev.jellyshort;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JellybeanFilterTest {
    private List<JellybeanFilter.Piece> observedPlant(int count) {
        List<JellybeanFilter.Piece> pieces = new ArrayList<>();
        for (int i = 0; i < count; i++) pieces.add(new JellybeanFilter.Piece(914047 + i, -4.0, 72.0 + i, 187.0));
        return pieces;
    }
    @Test void observedTenTierPlantKeepsTwoBottomPieces() {
        var selected = JellybeanFilter.select(observedPlant(10), 2, false);
        assertEquals(8, selected.size());
        assertFalse(selected.contains(914047)); assertFalse(selected.contains(914048));
        assertTrue(selected.contains(914049)); assertTrue(selected.contains(914056));
        assertEquals(10, JellybeanFilter.select(observedPlant(10), 2, true).size());
    }
    @Test void shorterPlantsAndPartiallyLoadedColumnsRemainVisible() {
        assertTrue(JellybeanFilter.select(observedPlant(9), 2, false).isEmpty());
        var pieces = observedPlant(10); pieces.remove(4);
        assertTrue(JellybeanFilter.select(pieces, 2, true).isEmpty());
        pieces = observedPlant(9);
        pieces.add(new JellybeanFilter.Piece(100, -3.0, 81, 187));
        assertTrue(JellybeanFilter.select(pieces, 2, false).isEmpty());
    }
    @Test void namedHeadDistinguishesJellybeanFromPetOrOtherCrop() {
        assertTrue(JellybeanFilter.matchesName("§9magicjellybean1"));
        assertFalse(JellybeanFilter.matchesName("Rose Dragon"));
        assertFalse(JellybeanFilter.matchesName("Armor Stand"));
        assertFalse(JellybeanFilter.matchesName("magicjellybean seeds"));
    }
}
