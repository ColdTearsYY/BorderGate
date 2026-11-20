package dev.bordergate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MenuAccessPolicyTest {
    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,true"})
    void playerCommandsRequireBothPermissions(boolean use, boolean command, boolean allowed) {
        assertEquals(allowed, MenuAccessPolicy.canUseCommands(use, command));
    }

    @ParameterizedTest
    @CsvSource({"false,false,false,false", "false,false,true,false", "false,true,false,false",
            "false,true,true,false", "true,false,false,false", "true,false,true,false",
            "true,true,false,false", "true,true,true,true"})
    void joinOpeningRequiresSettingAndBothPermissions(boolean setting, boolean use, boolean command, boolean allowed) {
        assertEquals(allowed, MenuAccessPolicy.canAutoOpen(setting, use, command));
    }
}
