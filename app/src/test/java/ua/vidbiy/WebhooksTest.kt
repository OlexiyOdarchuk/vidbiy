package ua.vidbiy

import org.junit.Assert.assertEquals
import org.junit.Test

class WebhooksTest {
    @Test
    fun substituteEscapesJson() {
        val vars = mapOf("{place}" to "м. \"Київ\"", "{event}" to "clear")
        assertEquals("""{"e":"clear","p":"м. \"Київ\""}""", Webhooks.substitute("""{"e":"{event}","p":"{place}"}""", vars, json = true))
        assertEquals("clear м. \"Київ\"", Webhooks.substitute("{event} {place}", vars))
    }

    @Test
    fun parsesHeaders() {
        assertEquals(
            mapOf("Authorization" to "Bearer abc: def", "X-A" to "1"),
            Webhooks.parseHeaders("Authorization: Bearer abc: def\n\nбез двокрапки\nX-A:1"),
        )
    }

    @Test
    fun roundTrip() {
        val list = listOf(Webhook(3, name = "Світло", url = "http://ha.local/api/webhook/x", events = setOf("clear")))
        assertEquals(list, Webhooks.parse(Webhooks.write(list)))
    }
}
