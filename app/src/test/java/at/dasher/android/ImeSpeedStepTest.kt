package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** Android #63 stepper formula (review I5): 1/40th of range, floor 10. */
class ImeSpeedStepTest {
    @Test fun historic_default_range_steps_ten() = assertEquals(10, speedStepFor(20..400))

    @Test fun wide_manifest_range_steps_fifteen() = assertEquals(15, speedStepFor(1..625))

    @Test fun narrow_range_floors_at_ten() = assertEquals(10, speedStepFor(40..60))

    @Test fun degenerate_single_value_range_still_ten() = assertEquals(10, speedStepFor(100..100))
}
