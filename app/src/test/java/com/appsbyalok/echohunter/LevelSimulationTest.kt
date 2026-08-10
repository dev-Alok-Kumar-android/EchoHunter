package com.appsbyalok.echohunter

import com.appsbyalok.echohunter.data.LevelEngine
import com.appsbyalok.echohunter.data.LevelFeature
import com.appsbyalok.echohunter.data.MazeGenerator
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.modes.BombObjective
import com.appsbyalok.echohunter.modes.DefenseObjective
import com.appsbyalok.echohunter.modes.EliminationObjective
import com.appsbyalok.echohunter.modes.EscapeObjective
import com.appsbyalok.echohunter.modes.IGameObjective
import com.appsbyalok.echohunter.modes.StandardObjective
import com.appsbyalok.echohunter.modes.StoryObjective
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedList
import java.util.Queue

class LevelSimulationTest {

    @Test
    fun testComprehensiveCampaignLevels() {
        val gs = GameState()
        gs.gameMode = GameModeId.CAMPAIGN // Campaign

        for (level in 1..200) {
            gs.currentLevel = level
            gs.resetGame()

            val config = LevelEngine.getLevelConfig(level)
            val objective = gs.activeObjective

            // 1. Verify Objective Logic
            verifyObjectiveMapping(config.features, objective)

            val grid = MazeGenerator.generateLevelMap(level, gs.gameMode.id, gs.difficulty.id, level)
            gs.gridMap = grid
            
            val screenW = 1080f
            val screenH = 2400f
            val scale = 1080f
            
            gs.tileSize = scale * 0.15f
            gs.mapWidth = grid.size * gs.tileSize
            gs.mapHeight = grid[0].size * gs.tileSize
            
            val enemySys = com.appsbyalok.echohunter.systems.EnemySystem()
            enemySys.respawnAll(gs)
            val effectSys = com.appsbyalok.echohunter.systems.EffectSystem()
            val spawnerSys = com.appsbyalok.echohunter.systems.SpawnerSystem(enemySys, effectSys)
            
            spawnerSys.generateNodes(gs, gs.mapWidth, gs.mapHeight, scale)
            objective.setupObjective(gs, enemySys, spawnerSys, screenW, screenH, scale)
            
            verifyMapSize(level, gs.gameMode, gs.difficulty.id, grid)
            identifySoftLock(gs, grid)

            // 3. Connectivity Test (BFS)
            assertTrue("L$level: No path from Spawn to Core", isPathPossible(grid))

            // 4. Enemy Density Check
            val spawnRate = config.spawnRateMultiplier
            if (config.features.contains(LevelFeature.ADMIN_BONUS)) {
                assertTrue("L$level: Admin bonus spawn rate unexpected ($spawnRate)", spawnRate > 0f)
            } else {
                assertTrue("L$level: Spawn rate too low ($spawnRate)", spawnRate >= 1.0f)
            }
            
            // 5. Specific check for Level 143 (The Buggy Combination)
            if (level == 143) {
                val hasEscape = config.features.contains(LevelFeature.ESCAPE)
                val hasDefense = config.features.contains(LevelFeature.DEFENSE)
                assertTrue("L143 must be a hybrid level", hasEscape && hasDefense)
                assertTrue("L143 Objective must be Escape (Master)", objective is EscapeObjective)
                assertTrue("L143 Defense logic missing from objective", gs.coreHp > 0)
            }
        }
    }

    @Test
    fun testStoryModeLogic() {
        val gs = GameState()
        gs.gameMode = GameModeId.STORY // Story Mode
        
        for (act in 0..2) {
            gs.selectedStoryAct = act
            gs.resetGame()
            
            assertTrue("Story Mode must use StoryObjective", gs.activeObjective is StoryObjective)
            
            // Check if act-specific maze generation works
            val grid = MazeGenerator.generateLevelMap(1, gs.gameMode.id, 0, 100, act)
            assertNotNull("Story Grid null for Act $act", grid)
            assertTrue("Story Act $act grid too small", grid.size > 20)
            
            verifyMapSize(1, gs.gameMode, 0, grid, act)
            identifySoftLock(gs, grid)
        }
    }

    @Test
    fun testIntMaxLevelStability() {
        val gs = GameState()
        gs.gameMode = GameModeId.CAMPAIGN
        gs.currentLevel = Int.MAX_VALUE
        gs.resetGame()

        val grid = MazeGenerator.generateLevelMap(gs.currentLevel, gs.gameMode.id, 0, 999)
        assertNotNull("INT_MAX Grid null", grid)
        
        val scale = 1080f
        gs.tileSize = scale * 0.15f
        gs.mapWidth = grid.size * gs.tileSize
        gs.mapHeight = grid[0].size * gs.tileSize
        
        val enemySys = com.appsbyalok.echohunter.systems.EnemySystem()
        enemySys.respawnAll(gs)
        val effectSys = com.appsbyalok.echohunter.systems.EffectSystem()
        val spawnerSys = com.appsbyalok.echohunter.systems.SpawnerSystem(enemySys, effectSys)
        
        spawnerSys.generateNodes(gs, gs.mapWidth, gs.mapHeight, scale)
        gs.activeObjective.setupObjective(gs, enemySys, spawnerSys, 1080f, 2400f, scale)

        verifyMapSize(gs.currentLevel, gs.gameMode, 0, grid)
        identifySoftLock(gs, grid)
    }

    @Test
    fun testTrainingObjectiveStability() {
        val gs = GameState()
        gs.gameMode = GameModeId.TRAINING
        gs.resetGame()
        
        val grid = MazeGenerator.generateLevelMap(1, gs.gameMode.id, 0, 101)
        gs.gridMap = grid
        val scale = 1080f
        gs.tileSize = scale * 0.15f
        
        val enemySys = com.appsbyalok.echohunter.systems.EnemySystem()
        enemySys.respawnAll(gs)
        val effectSys = com.appsbyalok.echohunter.systems.EffectSystem()
        val spawnerSys = com.appsbyalok.echohunter.systems.SpawnerSystem(enemySys, effectSys)
        
        gs.activeObjective.setupObjective(gs, enemySys, spawnerSys, 1080f, 2400f, scale)
        
        assertTrue("Training must use TrainingObjective", gs.activeObjective is com.appsbyalok.echohunter.modes.TrainingObjective)
        identifySoftLock(gs, grid)
    }

    private fun verifyObjectiveMapping(features: Set<LevelFeature>, objective: IGameObjective) {
        when {
            features.contains(LevelFeature.BOMB) -> assertTrue(objective is BombObjective)
            features.contains(LevelFeature.ESCAPE) -> assertTrue(objective is EscapeObjective)
            features.contains(LevelFeature.DEFENSE) -> assertTrue(objective is DefenseObjective)
            features.contains(LevelFeature.ELIMINATION) -> assertTrue(objective is EliminationObjective)
            features.contains(LevelFeature.CLEAN_SWEEP) -> assertTrue(objective is com.appsbyalok.echohunter.modes.CleanSweepObjective)
            else -> assertTrue(objective is StandardObjective)
        }
    }

    private fun verifyMapSize(level: Int, gameMode: GameModeId, difficulty: Int, grid: Array<IntArray>, storyAct: Int = 0) {
        val w = grid.size
        val isHard = difficulty == 1
        val maxAllowed = if (isHard) 251 else 151
        
        assertTrue("Map too small for level $level", w >= 21)
        assertTrue("Map exceeds maximum constraints ($maxAllowed)", w <= maxAllowed + 1)
        
        // Identify if this level SHOULD have been a Quarantine layout
        val features = LevelEngine.determineLevelFeatures(level)
        val isQuarantine = features.contains(LevelFeature.DEFENSE) && 
                !features.contains(LevelFeature.BOSS) && 
                !features.contains(LevelFeature.ESCAPE) &&
                !features.contains(LevelFeature.CLEAN_SWEEP) &&
                !features.contains(LevelFeature.MAZE) &&
                !features.contains(LevelFeature.ADMIN_BONUS)
        
        if (isQuarantine && gameMode == GameModeId.CAMPAIGN) {
            assertTrue("Quarantine map for L$level should be compact (w=$w)", w <= 43)
        }
        
        if (gameMode == GameModeId.STORY) {
            val expectedMin = if (isHard) 151 else 101
            assertTrue("Story map for Act $storyAct too small (w=$w)", w >= expectedMin)
        }
    }

    private fun identifySoftLock(gs: GameState, grid: Array<IntArray>) {
        val level = gs.currentLevel
        val objective = gs.activeObjective
        val features = LevelEngine.determineLevelFeatures(level)

        // 1. Structural Check: Essential Nodes
        var hasSpawn = false
        var hasDest = false
        for (x in grid.indices) {
            for (y in grid[0].indices) {
                if (grid[x][y] == MazeGenerator.PLAYER_SPAWN) hasSpawn = true
                if (grid[x][y] == MazeGenerator.DEST_NODE) hasDest = true
            }
        }
        assertTrue("L$level: Player Spawn missing", hasSpawn)
        
        // Standard objectives and Story usually need a DEST_NODE for core/portal
        if (objective is StoryObjective || objective is EscapeObjective || objective is DefenseObjective) {
            assertTrue("L$level: Destination Node missing for objective ${objective.javaClass.simpleName}", hasDest)
        }

        // 2. Reachability: DEST_NODE check
        if (hasDest) {
            assertTrue("L$level: Objective node unreachable from spawn", isPathPossible(grid))
        }

        // 3. Entity Check: Can objective be completed?
        if (features.contains(LevelFeature.CLEAN_SWEEP)) {
            // Must have at least some spawners to destroy
            assertTrue("L$level: Clean Sweep requested but no spawners found", gs.spawnerNodes.isNotEmpty())
        }

        if (features.contains(LevelFeature.ELIMINATION)) {
            // Ensure target count is initialized
            assertTrue("L$level: Elimination target count not set", gs.elimTargetsRequired > 0)
        }

        // 4. Hybrid Logic: Verify no conflicting markers
        if (features.contains(LevelFeature.DEFENSE) && features.contains(LevelFeature.ESCAPE)) {
            assertTrue("Hybrid L$level: Core position must be valid", gs.coreX > 0 && gs.coreY > 0)
        }
    }

    private fun isPathPossible(grid: Array<IntArray>): Boolean {
        var start = Pair(-1, -1)
        var end = Pair(-1, -1)
        for (x in grid.indices) {
            for (y in grid[0].indices) {
                if (grid[x][y] == MazeGenerator.PLAYER_SPAWN) start = x to y
                if (grid[x][y] == MazeGenerator.DEST_NODE) end = x to y
            }
        }
        if ((start.first == -1) || (end.first == -1)) return false

        val q: Queue<Pair<Int, Int>> = LinkedList()
        q.add(start)
        val visited = mutableSetOf(start)
        val dirs = arrayOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

        while (q.isNotEmpty()) {
            val curr = q.poll() ?: continue
            if (curr == end) return true
            for (d in dirs) {
                val next = curr.first + d.first to curr.second + d.second
                if (next.first in grid.indices && next.second in grid[0].indices &&
                    grid[next.first][next.second] != MazeGenerator.WALL && !visited.contains(next)) {
                    visited.add(next)
                    q.add(next)
                }
            }
        }
        return false
    }
}
