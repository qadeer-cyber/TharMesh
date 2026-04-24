package com.tharmesh.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tharmesh.app.R
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Process-wide source of truth for "who's nearby on the mesh".
 *
 * Populated with a fixed demo roster so the UI has something to render before the real
 * Nearby transport is wired. Every [TICK_MS] it nudges signal/distance/battery and
 * randomly toggles a node online/offline — this makes the Devices screen feel like
 * active hardware rather than a static list.
 *
 * When the real transport is ready, [upsertFromTransport] / [removeByUserId] will be
 * called from [com.tharmesh.dtn.MeshEngine] and the simulation loop turned off.
 */
class NearbyDirectory(
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val _nodes: MutableStateFlow<List<MeshNode>> = MutableStateFlow(seedRoster())
    val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val startedAt: Long = now()
    private var simulationJob: Job? = null

    fun startSimulation() {
        if (simulationJob?.isActive == true) return
        simulationJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    /** Online nodes ranked best-first via [MeshNode.score]. */
    fun onlineRanked(): List<MeshNode> = _nodes.value
        .filter { it.online }
        .sortedByDescending { it.score() }

    /** Number of peers currently reachable. Drives "SOS sent to N nodes" / health card. */
    fun onlineCount(): Int = _nodes.value.count { it.online }

    private fun tick() {
        val elapsedMin = ((now() - startedAt) / 60_000L).toInt().coerceIn(0, 60)
        val next = _nodes.value.map { node ->
            // Small random walk on signal, distance, battery so the UI feels live.
            val newSignal = (node.signal + random.nextInt(-6, 7)).coerceIn(20, 100)
            val newDistance = (node.distance + (random.nextFloat() - 0.5f) * 0.8f)
                .coerceIn(1.0f, 25.0f)
            val newBattery = max(5, node.battery - if (random.nextInt(10) == 0) 1 else 0)
            // ~6% chance each tick a node flips online/offline — gives fade-in/fade-out.
            val flip = random.nextInt(100) < 6
            val online = if (flip) !node.online else node.online
            node.copy(
                signal = newSignal,
                distance = newDistance,
                battery = newBattery,
                online = online,
                uptimeMinutes = if (online) min(60, node.uptimeMinutes + 1) else 0
            )
        }
        // Never let everyone disappear at once — keep the graph populated.
        val stabilized = if (next.none { it.online }) next.map { it.copy(online = true) } else next
        val withElapsed = stabilized.map {
            if (it.online && it.uptimeMinutes == 0) it.copy(uptimeMinutes = elapsedMin) else it
        }
        _nodes.value = withElapsed
    }

    private fun seedRoster(): List<MeshNode> = listOf(
        MeshNode(
            userId = "mesh-arjun-verma",
            name = "Arjun Verma",
            distance = 2.5f, signal = 88, battery = 92, uptimeMinutes = 18,
            online = true, avatarBg = R.drawable.bg_avatar
        ),
        MeshNode(
            userId = "mesh-meera-joshi",
            name = "Meera Joshi",
            distance = 5.1f, signal = 82, battery = 74, uptimeMinutes = 12,
            online = true, avatarBg = R.drawable.bg_avatar_green
        ),
        MeshNode(
            userId = "mesh-rohit-singh",
            name = "Rohit Singh",
            distance = 7.3f, signal = 66, battery = 58, uptimeMinutes = 9,
            online = true, avatarBg = R.drawable.bg_avatar_amber
        ),
        MeshNode(
            userId = "mesh-kavya-sharma",
            name = "Kavya Sharma",
            distance = 8.8f, signal = 61, battery = 80, uptimeMinutes = 6,
            online = true, avatarBg = R.drawable.bg_avatar
        ),
        MeshNode(
            userId = "mesh-priya-iyer",
            name = "Priya Iyer",
            distance = 12.4f, signal = 42, battery = 35, uptimeMinutes = 3,
            online = true, avatarBg = R.drawable.bg_avatar_green
        ),
        MeshNode(
            userId = "mesh-ibrahim-khan",
            name = "Ibrahim Khan",
            distance = 18.7f, signal = 28, battery = 41, uptimeMinutes = 2,
            online = false, avatarBg = R.drawable.bg_avatar_amber
        )
    )

    companion object {
        const val TICK_MS: Long = 3_000L
    }
}
