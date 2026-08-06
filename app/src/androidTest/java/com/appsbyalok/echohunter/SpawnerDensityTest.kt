package com.appsbyalok.echohunter

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appsbyalok.echohunter.data.MazeGenerator
import com.appsbyalok.echohunter.engine.DifficultyLevel
import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.modes.StandardObjective
import com.appsbyalok.echohunter.systems.EffectSystem
import com.appsbyalok.echohunter.systems.EnemySystem
import com.appsbyalok.echohunter.systems.SpawnerSystem
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpawnerDensityTest {

    @Test
    fun testSpawnerDensityAcrossLevels() {
        val levelsToTest = listOf(1, 10, 50, 100, 500, 1000, 10000, 100000, 1000000, 2147483647)
        
        val enemySys = EnemySystem()
        val effectSys = EffectSystem()
        val spawnerSys = SpawnerSystem(enemySys, effectSys)
        
        for (level in levelsToTest) {
            val gs = GameState()
            gs.currentLevel = level
            gs.difficulty = DifficultyLevel.HARD // Hard mode for max spawners
            gs.gameMode = GameModeId.CAMPAIGN // Campaign
            gs.activeObjective = StandardObjective() // Default
            
            // Mock map generation similar to GameEngine
            val scale = 1.0f
            val seed = 1000 + level
            gs.gridMap = MazeGenerator.generateLevelMap(level, gs.gameMode.id, gs.difficulty.id, seed)
            
            val columns = gs.gridMap!!.size
            val rows = gs.gridMap!![0].size
            gs.tileSize = scale * 0.15f
            gs.mapWidth = columns * gs.tileSize
            gs.mapHeight = rows * gs.tileSize
            
            // Find player spawn to set px, py
            for (x in 0 until columns) {
                for (y in 0 until rows) {
                    if (gs.gridMap!![x][y] == MazeGenerator.PLAYER_SPAWN) {
                        gs.px = x * gs.tileSize + (gs.tileSize / 2f)
                        gs.py = y * gs.tileSize + (gs.tileSize / 2f)
                    }
                }
            }

            spawnerSys.generateNodes(gs, gs.mapWidth, gs.mapHeight, scale)
            
            val nodeCount = gs.spawnerNodes.size
            val area = columns * rows
            val density = nodeCount.toFloat() / area
            val minDensity = 1f / 625f
            
            Log.d("SpawnerDensityTest", "Level: $level, Map: ${columns}x${rows}, Spawners: $nodeCount, Density: ${String.format("%.6f", density)} (Min Required: ${String.format("%.6f", minDensity)})")
            
            // Check if density meets the new minimum requirement
            if (level > 100) {
                assert(density >= minDensity * 0.9f) // 10% buffer for rounding/placement failures
            }
        }
    }
}
