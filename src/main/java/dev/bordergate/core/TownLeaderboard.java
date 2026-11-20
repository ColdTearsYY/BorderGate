package dev.bordergate.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public local-town ranking; only live Lands identities with positive historical scores. */
public final class TownLeaderboard {
    private TownLeaderboard() {}
    public record Entry(int rank, LandAffiliation land, BigDecimal score) {}

    public static List<Entry> rank(List<LandAffiliation> currentLands, Map<String, BigDecimal> scores) {
        if (currentLands == null || scores == null) throw new IllegalArgumentException("Missing town ranking data");
        Map<String, LandAffiliation> unique = new LinkedHashMap<>();
        for (LandAffiliation land : currentLands) if (land != null) unique.putIfAbsent(land.id(), land);
        List<LandAffiliation> eligible = unique.values().stream()
                .filter(land -> scores.get(land.id()) != null && scores.get(land.id()).signum() > 0)
                .sorted(Comparator.comparing((LandAffiliation land) -> scores.get(land.id())).reversed()
                        .thenComparing(LandAffiliation::name).thenComparing(LandAffiliation::id)).toList();
        List<Entry> result = new ArrayList<>(eligible.size());
        for (LandAffiliation land : eligible) result.add(new Entry(result.size() + 1, land, scores.get(land.id())));
        return List.copyOf(result);
    }
}
