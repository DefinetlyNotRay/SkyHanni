package dev.jellyshort;

import java.util.*;

/** Selection by named head and ten distinct vertical tiers, as observed in the entity dump. */
public final class JellybeanFilter {
    public record Piece(int id, double x, double y, double z) {}
    private record Column(int x, int z) {}

    public static boolean matchesName(String name) {
        return name.replaceAll("§.", "").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "").matches("magicjellybean[0-9]*");
    }

    public static Set<Integer> select(List<Piece> pieces, int height, boolean hideAll) {
        Map<Column, List<Piece>> columns = new HashMap<>();
        for (Piece p : pieces) columns.computeIfAbsent(
            new Column((int)Math.floor(p.x), (int)Math.floor(p.z)), key -> new ArrayList<>()).add(p);
        Set<Integer> result = new HashSet<>();
        for (List<Piece> column : columns.values()) {
            double base = column.stream().mapToDouble(Piece::y).min().orElseThrow();
            Set<Long> tiers = new HashSet<>();
            for (Piece p : column) tiers.add(Math.round(p.y - base));
            // Require all ten tiers; a partial chunk load or a shorter plant stays visible.
            boolean fullHeight = true;
            for (long tier = 0; tier < 10; tier++) if (!tiers.contains(tier)) fullHeight = false;
            if (!fullHeight) continue;
            for (Piece p : column) if (hideAll || p.y - base >= height - 0.01) result.add(p.id);
        }
        return result;
    }
}
