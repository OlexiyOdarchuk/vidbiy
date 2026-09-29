package ua.vidbiy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NightRulesTest {
    private val zone = ZoneId.of("Europe/Kyiv")

    private fun t(day: Int, h: Int, m: Int = 0) =
        LocalDateTime.of(2026, 9, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private val base = t(29, 7, 30)
    private val atNine = NightRule(1, from = 0, to = 6 * 60, action = RuleAction.AT, at = 9 * 60)

    @Test
    fun noAlertsKeepsTime() {
        assertEquals(RuleOutcome.None, NightRules.evaluate(listOf(atNine), base, emptyList(), zone))
    }

    @Test
    fun alertInWindowShiftsToFixedTime() {
        val r = NightRules.evaluate(listOf(atNine), base, listOf(t(29, 2)..t(29, 3)), zone)
        assertEquals(RuleOutcome.Shift(t(29, 9), 60 * 60_000L), r)
    }

    @Test
    fun alertBeforeWindowIgnored() {
        val r = NightRules.evaluate(listOf(atNine), base, listOf(t(28, 22)..t(28, 23)), zone)
        assertEquals(RuleOutcome.None, r)
    }

    @Test
    fun windowAcrossMidnightAndMinDuration() {
        val rule = NightRule(1, from = 22 * 60, to = 6 * 60, minDuration = 120, action = RuleAction.SKIP)
        val short = listOf(t(28, 23)..t(29, 0, 30))
        assertEquals(RuleOutcome.None, NightRules.evaluate(listOf(rule), base, short, zone))
        // Області й громада перекриваються — рахується об'єднання, а не сума.
        val overlapping = listOf(t(28, 23)..t(29, 1), t(29, 0)..t(29, 1, 30))
        assertEquals(RuleOutcome.Skip(150 * 60_000L), NightRules.evaluate(listOf(rule), base, overlapping, zone))
    }

    @Test
    fun skipBeatsShiftAndLatestShiftWins() {
        val later = NightRule(2, from = 0, to = 6 * 60, action = RuleAction.LATER, later = 180)
        val alerts = listOf(t(29, 1)..t(29, 2))
        assertEquals(RuleOutcome.Shift(t(29, 10, 30), 3_600_000L), NightRules.evaluate(listOf(atNine, later), base, alerts, zone))
        val skip = NightRule(3, from = 0, to = 6 * 60, action = RuleAction.SKIP)
        assertEquals(RuleOutcome.Skip(3_600_000L), NightRules.evaluate(listOf(atNine, skip), base, alerts, zone))
    }

    @Test
    fun fixedTimeEarlierThanAlarmIgnored() {
        val early = atNine.copy(at = 6 * 60)
        assertEquals(RuleOutcome.None, NightRules.evaluate(listOf(early), base, listOf(t(29, 2)..t(29, 3)), zone))
    }

    @Test
    fun windowClampedToAlarmTime() {
        // Вікно до 08:00, будильник о 07:30: тривога о 07:45 ще не могла вплинути.
        val rule = atNine.copy(to = 8 * 60)
        assertEquals(RuleOutcome.None, NightRules.evaluate(listOf(rule), base, listOf(t(29, 7, 45)..t(29, 8)), zone))
    }

    @Test
    fun disabledRuleIgnored() {
        val r = NightRules.evaluate(listOf(atNine.copy(enabled = false)), base, listOf(t(29, 2)..t(29, 3)), zone)
        assertEquals(RuleOutcome.None, r)
    }
}
