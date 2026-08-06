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

            // 2. Map Validation
            val grid = MazeGenerator.generateLevelMap(level, gs.gameMode.id, gs.difficulty.id, level)
            gs.gridMap = grid
            gs.tileSize = 100f
            
            val enemySys = com.appsbyalok.echohunter.systems.EnemySystem()
            val effectSys = com.appsbyalok.echohunter.systems.EffectSystem()
            val spawnerSys = com.appsbyalok.echohunter.systems.SpawnerSystem(enemySys, effectSys)
            
            objective.setupObjective(gs, enemySys, spawnerSys, 1080f, 1920f, 1.0f)

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
        }
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
