package com.appsbyalok.echohunter.systems

import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameState
import kotlin.math.sqrt

class EnemyAI {
    private var playerHeatMap: Array<IntArray>? = null
    private var coreHeatMap: Array<IntArray>? = null
    private var alertHeatMap: Array<IntArray>? = null

    companion object { private const val MAX_QUEUE = 65000 }
    private val qX = IntArray(MAX_QUEUE)
    private val qY = IntArray(MAX_QUEUE)

    fun updatePlayerHeatMap(gs: GameState) {
        val targetX = if (gs.isDecoyActive) gs.decoyX else gs.px
        val targetY = if (gs.isDecoyActive) gs.decoyY else gs.py
        if (gs.isCamouflaged && !gs.isDecoyActive) return
        playerHeatMap = buildHeatMap(gs, targetX, targetY, playerHeatMap)
    }

    fun updateCoreHeatMap(gs: GameState) {
        if (gs.coreRadius <= 0f) return
        coreHeatMap = buildHeatMap(gs, gs.coreX, gs.coreY, coreHeatMap)
    }

    private fun buildHeatMap(gs: GameState, targetX: Float, targetY: Float, existingMap: Array<IntArray>?): Array<IntArray>? {
        val grid = gs.gridMap ?: return null
        val w = grid.size; val h = grid[0].size

        var map = existingMap
        if (map == null || map.size != w || map[0].size != h) {
            map = Array(w) { IntArray(h) }
        }

        for(x in 0 until w) for(y in 0 until h) map[x][y] = 9999

        val ts = gs.tileSize
        val pxC = (targetX / ts).toInt().coerceIn(0, w - 1)
        val pyC = (targetY / ts).toInt().coerceIn(0, h - 1)

        var head = 0; var tail = 0
        qX[tail] = pxC; qY[tail] = pyC; tail++
        map[pxC][pyC] = 0

        val dirsX = intArrayOf(0, 0, -1, 1)
        val dirsY = intArrayOf(-1, 1, 0, 0)

        while (head < tail) {
            val cx = qX[head]; val cy = qY[head]; head++
            val dist = map[cx][cy]

            for (d in 0..3) {
                val nx = cx + dirsX[d]; val ny = cy + dirsY[d]
                if (nx in 0 until w && ny in 0 until h && grid[nx][ny] != 1) {
                    if (map[nx][ny] > dist + 1) {
                        map[nx][ny] = dist + 1
                        if (tail < MAX_QUEUE) {
                            qX[tail] = nx; qY[tail] = ny; tail++
                        }
                    }
                }
            }
        }
        return map
    }

    fun steerByPlayerHeatMap(ex: Float, ey: Float, evx: Float, evy: Float, speed: Float, gs: GameState, dt: Float): Pair<Float, Float> {
        return steerByMap(playerHeatMap, ex, ey, evx, evy, speed, gs, dt)
    }

    fun steerByCoreHeatMap(ex: Float, ey: Float, evx: Float, evy: Float, speed: Float, gs: GameState, dt: Float): Pair<Float, Float> {
        return steerByMap(coreHeatMap, ex, ey, evx, evy, speed, gs, dt)
    }

    fun updateAlertHeatMap(gs: GameState, targetX: Float, targetY: Float) {
        val grid = gs.gridMap ?: return
        val w = grid.size; val h = grid[0].size

        var map = alertHeatMap
        if (map == null || map.size != w || map[0].size != h) {
            map = Array(w) { IntArray(h) }
            for (x in 0 until w) for (y in 0 until h) map[x][y] = 9999
            alertHeatMap = map
        }

        val ts = gs.tileSize
        val pxC = (targetX / ts).toInt().coerceIn(0, w - 1)
        val pyC = (targetY / ts).toInt().coerceIn(0, h - 1)

        val radiusTiles = 35
        val startX = kotlin.math.max(0, pxC - radiusTiles)
        val endX = kotlin.math.min(w - 1, pxC + radiusTiles)
        val startY = kotlin.math.max(0, pyC - radiusTiles)
        val endY = kotlin.math.min(h - 1, pyC + radiusTiles)

        for (x in startX..endX) {
            for (y in startY..endY) {
                map[x][y] = 9999
            }
        }

        var head = 0; var tail = 0
        qX[tail] = pxC; qY[tail] = pyC; tail++
        map[pxC][pyC] = 0

        val dirsX = intArrayOf(0, 0, -1, 1)
        val dirsY = intArrayOf(-1, 1, 0, 0)

        while (head < tail) {
            val cx = qX[head]; val cy = qY[head]; head++
            val dist = map[cx][cy]
            if (dist > radiusTiles) continue

            for (d in 0..3) {
                val nx = cx + dirsX[d]; val ny = cy + dirsY[d]
                if (nx in startX..endX && ny in startY..endY && grid[nx][ny] != 1) {
                    if (map[nx][ny] > dist + 1) {
                        map[nx][ny] = dist + 1
                        if (tail < MAX_QUEUE) {
                            qX[tail] = nx; qY[tail] = ny; tail++
                        }
                    }
                }
            }
        }
    }

    fun steerByAlertHeatMap(ex: Float, ey: Float, evx: Float, evy: Float, speed: Float, gs: GameState, dt: Float): Pair<Float, Float> {
        return steerByMap(alertHeatMap, ex, ey, evx, evy, speed, gs, dt)
    }

    private fun steerByMap(hm: Array<IntArray>?, ex: Float, ey: Float, evx: Float, evy: Float, speed: Float, gs: GameState, dt: Float): Pair<Float, Float> {
        if (hm == null) return Pair(evx, evy)
        val ts = gs.tileSize
        val cx = (ex / ts).toInt()
        val cy = (ey / ts).toInt()

        if (cx !in hm.indices || cy !in hm[0].indices) return Pair(evx, evy)

        val config = com.appsbyalok.echohunter.data.LevelEngine.getLevelConfig(gs.currentLevel, gs.difficulty.id)
        if (kotlin.random.Random.nextFloat() > config.aiIntelligence) {
            return Pair(evx + (kotlin.random.Random.nextFloat() - 0.5f) * speed * dt * 5f, evy + (kotlin.random.Random.nextFloat() - 0.5f) * speed * dt * 5f)
        }

        var bestVal = hm[cx][cy]
        var targetX = ex; var targetY = ey

        val dirsX = intArrayOf(0, 0, -1, 1)
        val dirsY = intArrayOf(-1, 1, 0, 0)

        for (d in 0..3) {
            val nx = cx + dirsX[d]; val ny = cy + dirsY[d]
            if (nx in hm.indices && ny in hm[0].indices && hm[nx][ny] < bestVal) {
                bestVal = hm[nx][ny]
                targetX = nx * ts + (ts / 2f)
                targetY = ny * ts + (ts / 2f)
            }
        }

        val dx = targetX - ex; val dy = targetY - ey
        val dist = sqrt(dx * dx + dy * dy)
        
        // --- DIFFICULTY-BASED STEERING ---
        // Normal (0): 5f (Smooth turns), Hard (1): 10f (Sharp turns)
        val steerSharpness = if (gs.difficulty == DifficultyLevel.HARD) 10f else 5f
        val lerpFactor = (dt * steerSharpness).coerceIn(0f, 1f)
        
        return if (dist > 0f) {
            Pair((evx * (1f - lerpFactor)) + ((dx / dist) * speed * lerpFactor), 
                 (evy * (1f - lerpFactor)) + ((dy / dist) * speed * lerpFactor))
        } else Pair(evx, evy)
    }

    fun hasLineOfSight(x0: Float, y0: Float, x1: Float, y1: Float, gs: GameState): Boolean {
        if (gs.isCamouflaged && !gs.isDecoyActive) {
            val dx = x1 - x0
            val dy = y1 - y0
            val distSq = dx * dx + dy * dy
            val detectionRange = (gs.tileSize * 2.5f) * com.appsbyalok.echohunter.data.UpgradeSystem.getStealthDetectionMultiplier()
            if (distSq > detectionRange * detectionRange) return false
        }
        val grid = gs.gridMap ?: return true
        val ts = gs.tileSize
        val steps = 15
        val dx = (x1 - x0) / steps
        val dy = (y1 - y0) / steps

        for (i in 0..steps) {
            val cx = ((x0 + dx * i) / ts).toInt()
            val cy = ((y0 + dy * i) / ts).toInt()
            if (cx in grid.indices && cy in grid[0].indices) {
                if (grid[cx][cy] == 1) return false
            }
        }
        return true
    }
}