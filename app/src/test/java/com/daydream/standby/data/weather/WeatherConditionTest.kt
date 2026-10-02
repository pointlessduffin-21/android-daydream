package com.daydream.standby.data.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherConditionTest {
    @Test fun `maps wmo code groups`() {
        val expected = mapOf(
            0 to WeatherCondition.CLEAR, 1 to WeatherCondition.PARTLY_CLOUDY, 3 to WeatherCondition.CLOUDY,
            45 to WeatherCondition.FOG, 53 to WeatherCondition.DRIZZLE, 63 to WeatherCondition.RAIN,
            81 to WeatherCondition.RAIN, 75 to WeatherCondition.SNOW, 86 to WeatherCondition.SNOW,
            95 to WeatherCondition.THUNDERSTORM, 99 to WeatherCondition.THUNDERSTORM,
            -1 to WeatherCondition.UNKNOWN, 4 to WeatherCondition.UNKNOWN, 1000 to WeatherCondition.UNKNOWN,
        )
        expected.forEach { (code, condition) -> assertEquals("code $code", condition, WeatherCondition.fromWmoCode(code)) }
    }
}
