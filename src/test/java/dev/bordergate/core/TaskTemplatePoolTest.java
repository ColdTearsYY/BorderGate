package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskTemplatePoolTest {
    @Test void catalogueContains150StableAndDistinctSurvivalTemplates() {
        List<TaskTemplatePool.Template> templates = TaskTemplatePool.catalogue();
        assertEquals(150, templates.size());
        assertEquals(150, templates.stream().map(TaskTemplatePool.Template::id).distinct().count());
        Set<Set<String>> combinations = new HashSet<>();
        for (TaskTemplatePool.Template template : templates) {
            assertTrue(combinations.add(Set.copyOf(template.targets().keySet())), template.id());
            assertTrue(template.targets().size() >= 2 && template.targets().size() <= 4, template.id());
            assertTrue(template.requiredCount() >= 1 && template.requiredCount() <= template.targets().size(), template.id());
            assertTrue(template.targets().keySet().stream().allMatch(key -> key.startsWith("item:")), template.id());
            assertTrue(template.targets().values().stream().allMatch(v -> v.signum() > 0 && v.scale() == 0), template.id());
            if (template.targets().containsKey("item:ENCHANTING_TABLE")) assertTrue(template.targets().get("item:ENCHANTING_TABLE").compareTo(BigDecimal.ONE) <= 0);
        }
        assertEquals(150, combinations.size());
    }

    @Test void catalogueAndTargetsAreImmutableAndOrdered() {
        TaskTemplatePool.Template template = TaskTemplatePool.catalogue().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> TaskTemplatePool.catalogue().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> template.targets().put("DIAMOND", BigDecimal.ONE));
        assertEquals(List.copyOf(template.targets().keySet()), template.targets().keySet().stream().toList());
    }

    @Test void selectionIsDeterministicAndExcludesWhenPossible() {
        var excluded = Set.of(TaskTemplatePool.catalogue().getFirst().id());
        var first = TaskTemplatePool.select("world-1", "daily", excluded, item -> true);
        var second = TaskTemplatePool.select("world-1", "daily", excluded, item -> true);
        assertEquals(first, second);
        assertFalse(excluded.contains(first.id()));
    }

    @Test void selectionFallsBackToExcludedCompatibleTemplatesOnlyAfterAllExcluded() {
        var ids = TaskTemplatePool.catalogue().stream().map(TaskTemplatePool.Template::id).collect(java.util.stream.Collectors.toSet());
        var selected = TaskTemplatePool.select("world", "daily", ids, item -> true);
        assertNotNull(selected);
        assertTrue(ids.contains(selected.id()));
    }

    @Test void filtersWholeTemplatesAndReturnsNullWhenNothingIsAvailable() {
        assertNull(TaskTemplatePool.select("world", "daily", Set.of(), item -> false));
        var selected = TaskTemplatePool.select("world", "daily", Set.of(), item -> item.equals("OAK_LOG"));
        assertNull(selected);
    }

    @Test void scalesAllTemplateTargetsByCadenceWithoutChangingCompletionRequirements() {
        for (var base : TaskTemplatePool.catalogue()) {
            var available = base.targets().keySet();
            assertScaledTemplate(base, TaskTemplatePool.select("world", "daily", Set.of(), available::contains), 5);
            assertScaledTemplate(base, TaskTemplatePool.select("world", "weekly", Set.of(), available::contains), 7);
            assertScaledTemplate(base, TaskTemplatePool.select("world", "monthly", Set.of(), available::contains), 14);
        }
    }

    private static void assertScaledTemplate(TaskTemplatePool.Template base, TaskTemplatePool.Template selected, int multiplier) {
        assertNotNull(selected, base.id());
        assertEquals(base.id(), selected.id());
        assertEquals(base.title(), selected.title());
        assertEquals(base.requiredCount(), selected.requiredCount());
        assertEquals(List.copyOf(base.targets().keySet()), List.copyOf(selected.targets().keySet()));
        base.targets().forEach((key, value) -> assertEquals(value.multiply(BigDecimal.valueOf(multiplier)),
                selected.targets().get(key), base.id() + " " + key));
    }

    @Test void rejectsUnknownCadence() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskTemplatePool.select("world", "yearly", Set.of(), item -> true));
    }

    @Test void pluginStyleItemFilterFindsTemplatesWithDifferentCompletionThresholds() {
        assertNotNull(TaskTemplatePool.select("server:daily-date", "daily", Set.of(), resource -> resource.startsWith("item:")));
        assertEquals(2, TaskTemplatePool.catalogue().stream().map(TaskTemplatePool.Template::requiredCount).distinct().count());
    }
}
