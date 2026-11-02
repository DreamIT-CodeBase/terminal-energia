package com.terminalscout.app.prod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the org.json unit-test dependency. The stub android.jar returns defaults for every
 * org.json method, so without the real implementation on the test classpath this parses nothing
 * and every JSON-touching test silently passes while asserting nothing.
 */
class JsonClasspathSanityTest {
    @Test
    fun realJsonImplementationIsOnTheTestClasspath() {
        val o = JSONObject("""{"readings":[{"box":"A","digits":"1679"}]}""")
        val r = o.getJSONArray("readings").getJSONObject(0)
        assertEquals("A", r.getString("box"))
        assertEquals("1679", r.getString("digits"))
    }
}
