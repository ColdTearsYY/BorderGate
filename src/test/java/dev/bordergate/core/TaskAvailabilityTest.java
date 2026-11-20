package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TaskAvailabilityTest {
    @Test void eachMissingReasonIsSpecificInsteadOfUnconfigured() {
        for (TaskAvailability reason : TaskAvailability.values()) {
            assertFalse(reason.message("daily").contains("未配置"));
            assertFalse(reason.message("daily").contains("{cadence}"));
            assertTrue(reason.message("daily").contains("每日任务"));
        }
        assertTrue(TaskAvailability.DISABLED.message("daily").contains("停用"));
        assertTrue(TaskAvailability.NO_TEMPLATE.message("daily").contains("资源模板"));
        assertTrue(TaskAvailability.NO_TARGETS.message("daily").contains("手动目标"));
    }
    @Test void cadenceLabelsDistinguishMonthlyRecommendationFromDailyButton() {
        assertEquals("每日任务", TaskAvailability.cadenceLabel("daily"));
        assertEquals("每周任务", TaskAvailability.cadenceLabel("weekly"));
        assertEquals("每月任务", TaskAvailability.cadenceLabel("monthly"));
        assertThrows(IllegalArgumentException.class, () -> TaskAvailability.cadenceLabel("yearly"));
    }
    @Test void specificNewConfigKeysDoNotReuseOldMisleadingNoTaskText() {
        for (TaskAvailability reason : TaskAvailability.values()) {
            assertTrue(reason.configKey().startsWith("gui.task-"));
            assertNotEquals("gui.no-task", reason.configKey());
        }
    }
}
