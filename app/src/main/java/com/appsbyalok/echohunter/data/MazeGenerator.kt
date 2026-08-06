package com.appsbyalok.echohunter.data

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * MazeGenerator: Procedurally constructs game maps of various layouts (Arena, Quarantine,
 * Pillars, Server Farm, Labyrinth) depending on the active LevelFeatures and level index.
 */
object MazeGenerator {
    const val PATH = 0
    const val WALL = 1
    const val DEST_NODE = 2
    const val GUARD_SPAWN = 3
    const val PLAYER_SPAWN = 4

    enum class MazeType {
        ARENA,          // Open space for massive boss fights or admin loops
        QUARANTINE,     // Compact fortified room with corner obstacles for Core Defense
        PILLARS,        // Regular array of pillar blockades for cover
        SERVER_FARM,    // Interconnected terminal rooms and corridors
        LABYRINTH       // Complex, tight maze paths for escape objectives
    }

    fun generateLevelMap(
        level: Int,
        gameMode: Int,
        difficulty: Int,
        seed: Int,
        storyAct: Int = 0,
    ): Array<IntArray> {
        val config = LevelEngine.getLevelConfig(level)
        val rand = Random(seed)

        // Map LevelFeatures dynamically to spatial layouts
        val type = when {
            gameMode == 2 -> MazeType.LABYRINTH // Training concludes with a sonar-guided maze escape.
            config.features.contains(LevelFeature.ADMIN_BONUS) -> MazeType.ARENA
            config.features.contains(LevelFeature.ESCAPE) -> MazeType.LABYRINTH   // Escape/Maze take priority for layout
            config.features.contains(LevelFeature.CLEAN_SWEEP) -> MazeType.LABYRINTH // Intense hunt in complex paths
            config.features.contains(LevelFeature.MAZE) -> MazeType.LABYRINTH
            config.features.contains(LevelFeature.BOSS) -> MazeType.ARENA       // Bosses need wide-open kiting space
            config.features.contains(LevelFeature.DEFENSE) -> MazeType.QUARANTINE // Defense needs a specialized fortified shell
            level % 3 == 0 -> MazeType.PILLARS                                    // Fallback structural variety
            else -> MazeType.SERVER_FARM                                          // Default layout for classic sweeps
        }

        val maxGrid = if (difficulty == 1) 251 else 151

        // --- FIX: Use Long for calculation to prevent overflow before min() ---
        val calculatedSize = 21L + (level.toLong() * 2L)
        var w = min(maxGrid.toLong(), calculatedSize).toInt()
        var h = min(maxGrid.toLong(), calculatedSize).toInt()

        if (gameMode == 1) {
            val baseSize = if (difficulty == 1) 151 else 101
            val growth = if (difficulty == 1) 50 else 25
            val actSize = baseSize + (storyAct * growth)
            w = min(maxGrid, actSize)
            h = min(maxGrid, actSize)
        } else if (type == MazeType.QUARANTINE) {
            val qSize = min(41L, 21L + level.toLong()).toInt()
            w = qSize
            h = qSize
        }

        // Ensure dimension scales are odd for perfect alignment calculations
        if (w % 2 == 0) w++
        if (h % 2 == 0) h++

        val grid = Array(w) { IntArray(h) { WALL } }

        var startX = 2
        var startY = 2
        var destX = w - 3
        var destY = h - 3

        val isHybridDefenseEscape =
            config.features.contains(LevelFeature.ESCAPE) && config.features.contains(LevelFeature.DEFENSE)

        when (type) {
            MazeType.QUARANTINE -> {
                generateQuarantine(grid, w, h)
                // CORE DEFENSE: Core sits exactly in the middle; Player spawns safely below it
                destX = w / 2; destY = h / 2
                startX = destX; startY = destY + 2
            }

            MazeType.ARENA -> {
                generateArena(grid, w, h)
                // BOSS/ARENA layout: Player starts at bottom, objective/boss spawns in center
                destX = w / 2; destY = h / 2
                startX = destX; startY = h - 3
            }

            MazeType.PILLARS -> {
                generatePillars(grid, w, h)
                startX = 2; startY = 2
            }

            MazeType.SERVER_FARM -> {
                val rooms = generateServerFarm(grid, w, h, rand)
                if (rooms.isNotEmpty()) {
                    startX = rooms.first().centerX
                    startY = rooms.first().centerY
                    destX = if (isHybridDefenseEscape) w / 2 else rooms.last().centerX
                    destY = if (isHybridDefenseEscape) h / 2 else rooms.last().centerY
                }
            }

            MazeType.LABYRINTH -> {
                generateLabyrinth(grid, w, h, rand)
                startX = 2; startY = 2
                if (isHybridDefenseEscape) {
                    destX = w / 2
                    destY = h / 2
                }
            }
        }

        if (isHybridDefenseEscape) {
            // Clear a larger 7x7 room in the center for the hybrid Defense+Escape objective
            destX = w / 2
            destY = h / 2
            for (dx in -3..3) {
                for (dy in -3..3) {
                    val nx = destX + dx
                    val ny = destY + dy
                    if (nx in 1 until w - 1 && ny in 1 until h - 1) {
                        grid[nx][ny] = PATH
                    }
                }
            }
        }

        enforceOuterWalls(grid, w, h)

        // Clear player spawn and register code
        if (grid[startX][startY] == WALL) grid[startX][startY] = PATH
        grid[startX][startY] = PLAYER_SPAWN

        // Place destination key-node safely on open paths
        var foundDest = false
        if (grid[destX][destY] != WALL && (destX != startX || destY != startY)) {
            foundDest = true
        } else {
            // Find furthest path node from start for objective placement
            var maxDistSq = -1
            for (x in 1 until w - 1) {
                for (y in 1 until h - 1) {
                    if (grid[x][y] == PATH && (x != startX || y != startY)) {
                        val dSq = (x - startX) * (x - startX) + (y - startY) * (y - startY)
                        if (dSq > maxDistSq) {
                            maxDistSq = dSq
                            destX = x
                            destY = y
                            foundDest = true
                        }
                    }
                }
            }
        }

        // Emergency fallback: force a path at the edge if no path exists
        if (!foundDest) {
            destX = w - 2
            destY = h - 2
            grid[destX][destY] = PATH
        }
        grid[destX][destY] = DEST_NODE

        // Populate guards away from initial player position
        placeGuards(grid, w, h, rand, level, startX, startY)

        return grid
    }

    private fun generateArena(grid: Array<IntArray>, w: Int, h: Int) {
        for (x in 1 until w - 1) {
            for (y in 1 until h - 1) {
                grid[x][y] = PATH
            }
        }
    }

    private fun generateQuarantine(grid: Array<IntArray>, w: Int, h: Int) {
        generateArena(grid, w, h)
        val cw = w / 4
        val ch = h / 4
        // Seal off outer corners with protective bulkheads
        for (x in 1..cw) for (y in 1..ch) grid[x][y] = WALL
        for (x in w - 1 - cw until w - 1) for (y in 1..ch) grid[x][y] = WALL
        for (x in 1..cw) for (y in h - 1 - ch until h - 1) grid[x][y] = WALL
        for (x in w - 1 - cw until w - 1) for (y in h - 1 - ch until h - 1) grid[x][y] = WALL
    }

    private fun generatePillars(grid: Array<IntArray>, w: Int, h: Int) {
        generateArena(grid, w, h)
        // Draw physical grid obstructions for cover-shooting mechanics
        for (x in 3 until w - 3 step 4) {
            for (y in 3 until h - 3 step 4) {
                grid[x][y] = WALL
                grid[x + 1][y] = WALL
                grid[x][y + 1] = WALL
                grid[x + 1][y + 1] = WALL
            }
        }
    }

    data class Room(val x: Int, val y: Int, val w: Int, val h: Int) {
        val centerX: Int get() = x + w / 2
        val centerY: Int get() = y + h / 2
    }

    private fun generateServerFarm(
        grid: Array<IntArray>,
        w: Int,
        h: Int,
        rand: Random,
    ): List<Room> {
        val numRooms = max(3, (w * h) / 100)
        val rooms = mutableListOf<Room>()

        repeat(numRooms) {
            val rw = rand.nextInt(5, 13)
            val rh = rand.nextInt(5, 13)
            val rx = rand.nextInt(1, max(2, w - rw - 1))
            val ry = rand.nextInt(1, max(2, h - rh - 1))

            val room = Room(rx, ry, rw, rh)

            for (x in room.x until room.x + room.w) {
                for (y in room.y until room.y + room.h) {
                    if (x in 1 until w - 1 && y in 1 until h - 1) grid[x][y] = PATH
                }
            }

            rooms.lastOrNull()?.let { prev ->
                carveThickCorridor(
                    grid, prev.centerX, prev.centerY, room.centerX, room.centerY, w, h, rand
                )
            }
            rooms.add(room)
        }
        return rooms
    }

    private fun carveThickCorridor(
        grid: Array<IntArray>,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        w: Int,
        h: Int,
        rand: Random,
    ) {
        var cx = x1
        var cy = y1

        fun dig(x: Int, y: Int) {
            for (ox in 0..1) for (oy in 0..1) {
                if (x + ox in 1 until w - 1 && y + oy in 1 until h - 1) grid[x + ox][y + oy] = PATH
            }
        }

        if (rand.nextBoolean()) {
            while (cx != x2) {
                dig(cx, cy); cx += if (x2 > cx) 1 else -1
            }
            while (cy != y2) {
                dig(cx, cy); cy += if (y2 > cy) 1 else -1
            }
        } else {
            while (cy != y2) {
                dig(cx, cy); cy += if (y2 > cy) 1 else -1
            }
            while (cx != x2) {
                dig(cx, cy); cx += if (x2 > cx) 1 else -1
            }
        }
    }

    private fun generateLabyrinth(grid: Array<IntArray>, w: Int, h: Int, rand: Random) {
        val stack = mutableListOf<Pair<Int, Int>>()
        val start = Pair(2, 2)
        grid[start.first][start.second] = PATH
        stack.add(start)

        while (stack.isNotEmpty()) {
            val current = stack.last()
            val (x, y) = current

            val dirs = mutableListOf(
                intArrayOf(0, -2), intArrayOf(0, 2), intArrayOf(2, 0), intArrayOf(-2, 0)
            )
            dirs.shuffle(rand)

            var found = false
            for (dir in dirs) {
                val nx = x + dir[0]
                val ny = y + dir[1]
                if (nx in 1 until w - 1 && ny in 1 until h - 1 && grid[nx][ny] == WALL) {
                    // Remove wall
                    grid[x + dir[0] / 2][y + dir[1] / 2] = PATH
                    // Mark visited
                    grid[nx][ny] = PATH
                    stack.add(Pair(nx, ny))
                    found = true
                    break
                }
            }

            if (!found) {
                stack.removeAt(stack.size - 1)
            }
        }

        // Inject path loops dynamically to prevent strict, frustrating dead-ends in intense gameplay
        val loopCount = (w * h) / 30
        var loopsMade = 0
        var attempts = 0
        while (loopsMade < loopCount && attempts < loopCount * 5) {
            attempts++
            val rx = rand.nextInt(2, w - 2)
            val ry = rand.nextInt(2, h - 2)
            if (grid[rx][ry] == WALL) {
                val horizon =
                    grid[rx - 1][ry] == PATH && grid[rx + 1][ry] == PATH && grid[rx][ry - 1] == WALL && grid[rx][ry + 1] == WALL
                val vert =
                    grid[rx][ry - 1] == PATH && grid[rx][ry + 1] == PATH && grid[rx - 1][ry] == WALL && grid[rx + 1][ry] == WALL
                if (horizon || vert) {
                    grid[rx][ry] = PATH
                    loopsMade++
                }
            }
        }
    }

    private fun placeGuards(
        grid: Array<IntArray>,
        w: Int,
        h: Int,
        rand: Random,
        level: Int,
        px: Int,
        py: Int,
    ) {
        // Density-based guard placement instead of linear level scaling
        // Linear scaling (level * 2) hits integer limits or becomes unmanageable
        val area = w * h
        val baseGuards = 5
        val densityFactor =
            LevelEngine.getSaturatedValue(level, 0.002f, 0.01f, 500f) // 0.2% to 1% of area
        val guardCount = (baseGuards + (area * densityFactor)).toInt().coerceAtMost(1000)

        var placed = 0
        var attempts = 0
        while (placed < guardCount && attempts < 2000) {
            attempts++
            val gx = rand.nextInt(1, w - 1)
            val gy = rand.nextInt(1, h - 1)

            val distSq = (gx - px) * (gx - px) + (gy - py) * (gy - py)

            // Spawn guards away from the player's immediate starting viewport
            if (grid[gx][gy] == PATH && distSq > 25) {
                grid[gx][gy] = GUARD_SPAWN
                placed++
            }
        }
    }

    private fun enforceOuterWalls(grid: Array<IntArray>, w: Int, h: Int) {
        for (x in 0 until w) {
            grid[x][0] = WALL
            grid[x][h - 1] = WALL
        }
        for (y in 0 until h) {
            grid[0][y] = WALL
            grid[w - 1][y] = WALL
        }
    }
}
