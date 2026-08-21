package com.example.myagent.global.configuration;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.myagent.global.configuration.ParityProfileProperties.ExecutionLimits;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParityProfilePropertiesTest {

    @Test
    void rejectsMoreThanTwoPatchRetries() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new ParityProfileProperties(
                Map.of(), new ExecutionLimits(3, 2), Path.of(".agent/runtime")
            )
        );
    }

    @Test
    void rejectsMoreThanSixParityWorkers() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new ParityProfileProperties(
                Map.of(), new ExecutionLimits(2, 7), Path.of(".agent/runtime")
            )
        );
    }
}
