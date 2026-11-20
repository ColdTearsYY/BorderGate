package dev.bordergate.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuestCycle {
    private final String id;
    private final String title;
    private final String cadence;
    private final Map<String, BigDecimal> targets;
    private final int requiredCount;
    private final BigDecimal scoreMultiplier;
    private final Map<String, BigDecimal> contributed = new LinkedHashMap<>();
    private final Map<UUID, BigDecimal> scores = new LinkedHashMap<>();

    public QuestCycle(String id, String title, String cadence, Map<String, BigDecimal> targets,
                      int requiredCount, BigDecimal scoreMultiplier) {
        if (id == null || id.isBlank() || targets == null || targets.isEmpty()) throw new IllegalArgumentException("Quest needs an id and targets");
        this.id = id;
        this.title = title == null ? id : title;
        this.cadence = cadence == null ? "daily" : cadence;
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        this.requiredCount = Math.max(1, Math.min(requiredCount, targets.size()));
        this.scoreMultiplier = scoreMultiplier == null || scoreMultiplier.signum() <= 0 ? BigDecimal.ONE : scoreMultiplier;
    }

    public boolean contribute(UUID player, String resource, BigDecimal amount, BigDecimal resourceWeight) {
        if (player == null || resource == null || amount == null || amount.signum() <= 0) return false;
        BigDecimal target = targets.get(resource);
        if (target == null) return false;
        BigDecimal current = contributed.getOrDefault(resource, BigDecimal.ZERO);
        if (amount.compareTo(target.subtract(current)) > 0) return false;
        contributed.put(resource, current.add(amount));
        BigDecimal weight = resourceWeight == null || resourceWeight.signum() <= 0 ? BigDecimal.ONE : resourceWeight;
        BigDecimal points = amount.multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .divide(target, MathContext.DECIMAL64).multiply(weight).multiply(scoreMultiplier);
        scores.merge(player, points, BigDecimal::add);
        return true;
    }

    public boolean isComplete() { return completedResourceCount() >= requiredCount; }

    public int completedResourceCount() {
        return (int) targets.entrySet().stream().filter(entry -> contributed.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                .compareTo(entry.getValue()) >= 0).count();
    }

    public BigDecimal remaining(String resource) {
        BigDecimal target = targets.get(resource);
        return target == null ? BigDecimal.ZERO : target.subtract(contributed.getOrDefault(resource, BigDecimal.ZERO));
    }

    public BigDecimal completionPercent() {
        if (targets.isEmpty()) return BigDecimal.ZERO;
        return BigDecimal.valueOf(completedResourceCount()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(requiredCount), MathContext.DECIMAL64).min(BigDecimal.valueOf(100));
    }

    public BigDecimal playerPercent(UUID player) {
        BigDecimal total = scores.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) return BigDecimal.ZERO;
        return scores.getOrDefault(player, BigDecimal.ZERO).multiply(BigDecimal.valueOf(100))
                .divide(total, MathContext.DECIMAL64);
    }

    /**
     * Calculates rewards from a per-player base. In fixed mode every participant
     * receives the base; in pool mode the base is multiplied by participant count
     * before contribution percentages are applied.
     */
    public Map<UUID, BigDecimal> rewardShares(BigDecimal perPlayerReward, String mode, int decimalPlaces) {
        Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
        List<UUID> participants = participants();
        if (perPlayerReward == null || perPlayerReward.signum() <= 0 || participants.isEmpty()) return shares;
        int scale = Math.max(0, Math.min(decimalPlaces, 8));
        BigDecimal base = perPlayerReward.setScale(scale, RoundingMode.DOWN);
        if (base.signum() <= 0) return shares;
        if (!isPoolMode(mode)) {
            participants.forEach(player -> shares.put(player, base));
            return Collections.unmodifiableMap(shares);
        }
        return proportionalShares(base.multiply(BigDecimal.valueOf(participants.size())), participants, scale);
    }

    public Map<UUID, BigDecimal> rewardShares(BigDecimal perPlayerReward, String mode) {
        return rewardShares(perPlayerReward, mode, 2);
    }

    /** Compatibility overload for callers that already provide a total pool. */
    public Map<UUID, BigDecimal> rewardShares(BigDecimal rewardPool) {
        List<UUID> participants = participants();
        if (rewardPool == null || rewardPool.signum() <= 0 || participants.isEmpty()) return Map.of();
        return proportionalShares(rewardPool, participants, 2);
    }

    public BigDecimal totalReward(BigDecimal perPlayerReward, String mode, int decimalPlaces) {
        if (perPlayerReward == null || perPlayerReward.signum() <= 0 || participantCount() == 0) return BigDecimal.ZERO;
        int scale = Math.max(0, Math.min(decimalPlaces, 8));
        return perPlayerReward.setScale(scale, RoundingMode.DOWN).multiply(BigDecimal.valueOf(participantCount()));
    }

    public int participantCount() { return participants().size(); }

    private Map<UUID, BigDecimal> proportionalShares(BigDecimal pool, List<UUID> participants, int scale) {
        Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal total = participants.stream().map(scores::get).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) return shares;
        BigDecimal roundedPool = pool.setScale(scale, RoundingMode.DOWN);
        Map<UUID, BigDecimal> remainders = new LinkedHashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (UUID player : participants) {
            BigDecimal exact = roundedPool.multiply(scores.get(player)).divide(total, MathContext.DECIMAL128);
            BigDecimal rounded = exact.setScale(scale, RoundingMode.DOWN);
            shares.put(player, rounded);
            remainders.put(player, exact.subtract(rounded));
            allocated = allocated.add(rounded);
        }
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        int remainingUnits = roundedPool.subtract(allocated).movePointRight(scale).intValueExact();
        List<UUID> byRemainder = new ArrayList<>(participants);
        byRemainder.sort(Comparator.comparing((UUID player) -> remainders.get(player)).reversed().thenComparing(UUID::toString));
        for (int i = 0; i < remainingUnits; i++) {
            UUID player = byRemainder.get(i % byRemainder.size());
            shares.put(player, shares.get(player).add(unit));
        }
        return Collections.unmodifiableMap(shares);
    }

    private boolean isPoolMode(String mode) {
        return "pool".equalsIgnoreCase(mode) || "dividend".equalsIgnoreCase(mode)
                || "proportional".equalsIgnoreCase(mode);
    }

    private List<UUID> participants() {
        return scores.entrySet().stream().filter(entry -> entry.getValue() != null && entry.getValue().signum() > 0)
                .map(Map.Entry::getKey).toList();
    }

    public void restoreAggregates(Map<String, BigDecimal> totals, Map<UUID, BigDecimal> playerScores) {
        contributed.clear();
        scores.clear();
        if (totals != null) totals.forEach((resource, amount) -> {
            if (targets.containsKey(resource) && amount != null && amount.signum() > 0) contributed.put(resource, amount.min(targets.get(resource)));
        });
        if (playerScores != null) playerScores.forEach((player, score) -> {
            if (player != null && score != null && score.signum() > 0) scores.put(player, score);
        });
    }

    public String id() { return id; }
    public String title() { return title; }
    public String cadence() { return cadence; }
    public Map<String, BigDecimal> targets() { return targets; }
    public BigDecimal scoreMultiplier() { return scoreMultiplier; }
    public Map<String, BigDecimal> contributed() { return Collections.unmodifiableMap(contributed); }
    public Map<UUID, BigDecimal> scores() { return Collections.unmodifiableMap(scores); }
    public BigDecimal score(UUID player) { return scores.getOrDefault(player, BigDecimal.ZERO); }
    public int requiredCount() { return requiredCount; }
}
