package ua.vidbiy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StatsTest {
    private val zone = ZoneId.of("Europe/Kyiv")

    private fun t(day: Int, h: Int, m: Int = 0) =
        LocalDateTime.of(2026, 9, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun placeStatsMergeAndSplitAcrossMidnight() {
        val now = t(29, 12)
        val alerts = listOf(
            t(28, 23)..t(29, 1),        // через північ: 60 хв 28-го й 60 хв 29-го
            t(29, 0, 30)..t(29, 2),     // перекривається з попередньою (район і область)
            t(27, 14)..t(27, 14, 30),
        )
        val s = Stats.place(alerts, now, zone)!!
        assertEquals(2, s.count)
        assertEquals(105, s.avgMin)
        assertEquals(180, s.longestMin)
        assertEquals(0, s.nightShare) // нічні — ті, що почалися з 00:00 до 06:00
        assertEquals(listOf(30, 60, 120), s.perDay.takeLast(3))
        assertEquals(-1, s.perDay[s.perDay.size - 4]) // до найдавнішої тривоги даних немає
    }

    @Test
    fun nightEndHour() {
        val now = t(29, 12)
        val s = Stats.place(listOf(t(28, 2)..t(28, 3, 40), t(29, 1)..t(29, 3, 10)), now, zone)!!
        assertEquals(100, s.nightShare)
        assertEquals(3, s.nightEndHour)
    }

    @Test
    fun personalStats() {
        val now = t(29, 12)
        val sessions = listOf(
            HistorySession(t(29, 1), t(29, 4), listOf(
                HistoryEvent(t(29, 1), "", "armed"),
                HistoryEvent(t(29, 2), "", "alert"),
                HistoryEvent(t(29, 3), "", "clear"),
                HistoryEvent(t(29, 3), "", "ring"),
                HistoryEvent(t(29, 3, 4), "", "stop"),
            )),
            HistorySession(t(28, 7), t(28, 7), listOf(HistoryEvent(t(28, 7), "", "shift"))),
            // Понад 30 днів тому — не рахується.
            HistorySession(now - 40L * 86_400_000, now - 40L * 86_400_000, listOf(HistoryEvent(now - 40L * 86_400_000, "", "armed"))),
        )
        val s = Stats.personal(sessions, now)
        assertEquals(1, s.sessions)
        assertEquals(1, s.nightsWithAlert)
        assertEquals(1, s.rings)
        assertEquals(1, s.shifted)
        assertEquals(4, s.ringToStopMin)
        assertNull(Stats.place(emptyList(), now, zone))
    }
}
