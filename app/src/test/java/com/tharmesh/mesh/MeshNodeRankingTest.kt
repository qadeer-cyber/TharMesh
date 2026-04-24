package com.tharmesh.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the smart-ranking score = 0.5·signal + 0.3·battery + 0.2·uptime(%) formula
 * so the UI can rely on a stable ordering.
 */
class MeshNodeRankingTest {

    private fun node(
        id: String,
        signal: Int,
        battery: Int,
        uptime: Int,
        online: Boolean = true
    ) = MeshNode(
        userId = id,
        name = id,
        distance = 5.0f,
        signal = signal,
        battery = battery,
        uptimeMinutes = uptime,
        online = online,
        avatarBg = 0
    )

    @Test
    fun `offline nodes score zero`() {
        val n = node("a", signal = 100, battery = 100, uptime = 60, online = false)
        assertEquals(0.0, n.score(), 0.0001)
    }

    @Test
    fun `full stack node scores 100`() {
        val n = node("a", signal = 100, battery = 100, uptime = 60)
        // 0.5*100 + 0.3*100 + 0.2*100 = 100
        assertEquals(100.0, n.score(), 0.0001)
    }

    @Test
    fun `weights favour signal over battery over uptime`() {
        val strongSignal = node("signal", signal = 100, battery = 0, uptime = 0).score()
        val strongBattery = node("battery", signal = 0, battery = 100, uptime = 0).score()
        val strongUptime = node("uptime", signal = 0, battery = 0, uptime = 60).score()
        assertTrue("signal beats battery", strongSignal > strongBattery)
        assertTrue("battery beats uptime", strongBattery > strongUptime)
    }

    @Test
    fun `quality bucketing matches signal thresholds`() {
        assertEquals(MeshNode.Quality.STRONG, node("a", signal = 80, battery = 50, uptime = 10).quality())
        assertEquals(MeshNode.Quality.GOOD, node("a", signal = 60, battery = 50, uptime = 10).quality())
        assertEquals(MeshNode.Quality.FAIR, node("a", signal = 40, battery = 50, uptime = 10).quality())
        assertEquals(MeshNode.Quality.WEAK, node("a", signal = 20, battery = 50, uptime = 10).quality())
    }
}
