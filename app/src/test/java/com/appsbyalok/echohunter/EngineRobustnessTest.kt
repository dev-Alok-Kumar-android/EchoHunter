package com.appsbyalok.echohunter

import com.appsbyalok.echohunter.engine.AppStateId
import com.appsbyalok.echohunter.engine.GameState
import com.appsbyalok.echohunter.systems.triggerCinematicFocus
import com.appsbyalok.echohunter.systems.updateCameraLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRobustnessTest {

    @Test
    fun testCameraSystemStability() {
        val gs = GameState()
        val w = 1080f
        val h = 2400f
        val dt = 0.016f // 60 FPS

        // 1. Initial State Check
        gs.px = 500f
        gs.py = 500f
        gs.mapWidth = 2000f
        gs.mapHeight = 2000f
        gs.tileSize = 150f
        
        gs.updateCameraLogic(dt, w, h)
        
        assertFalse("Camera X should not be NaN", gs.cameraX.isNaN())
        assertFalse("Camera Y should not be NaN", gs.cameraY.isNaN())

        // 2. Cinematic Focus Trigger Test
        val enemyX = 1500f
        val enemyY = 1500f
        gs.triggerCinematicFocus(enemyX, enemyY, zoom = 1.5f, duration = 1.0f)

        // Run for a few frames
        repeat(10) { gs.updateCameraLogic(dt, w, h) }

        assertTrue("Camera should move towards focus point", gs.cameraFocusWeight > 0.5f)
        assertTrue("Zoom should increase", gs.cameraZoom > 1.0f)

        // 3. Return to Player Test
        gs.slowMoTimer = 0.1f // Force nearly finished
        repeat(30) { 
            gs.updateCameraLogic(dt, w, h) 
            gs.slowMoTimer -= dt
        }

        assertTrue("Camera weight should decay", gs.cameraFocusWeight < 0.2f)
        
        // 4. Boundary Enforcement Test
        gs.px = -100f // Move player out of bounds
        gs.updateCameraLogic(dt, w, h)
        assertTrue("Camera must not go below 0", gs.cameraX >= 0f)
        
        gs.px = 5000f // Move player way out
        gs.updateCameraLogic(dt, w, h)
        val maxPossibleX = gs.mapWidth - gs.getViewportW(w, h)
        assertTrue("Camera must not exceed map width", gs.cameraX <= maxPossibleX + 1f)
    }

    @Test
    fun testObjectPositionIntegrityDuringTriggers() {
        val gs = GameState()
        gs.px = 400f
        gs.py = 400f
        val initialX = gs.px
        val initialY = gs.py
        val initialTileSize = gs.tileSize

        // Trigger heavy cinematic effects
        gs.triggerCinematicFocus(1000f, 1000f, zoom = 2.5f, duration = 2.0f)
        gs.shakeAmount = 100f
        
        // Update logic multiple times
        repeat(20) {
            gs.updateTimers(0.016f, 1000f)
            // Note: px/py should only change if updatePlayerMovement is called with inputs
        }

        assertEquals("Player X must remain stable during cinematic triggers", initialX, gs.px, 0.001f)
        assertEquals("Player Y must remain stable during cinematic triggers", initialY, gs.py, 0.001f)
        assertEquals("World Scale (TileSize) must not shrink or grow", initialTileSize, gs.tileSize, 0.001f)
    }

    @Test
    fun testStateTransitionSanity() {
        val gs = GameState()
        assertEquals("Game must start in STORY_INTRO or MENU", AppStateId.STORY_INTRO, gs.state)

        // Simulate Reset
        gs.resetGame()
        assertNotNull("Objective must be initialized", gs.activeObjective)
        assertFalse("Level should not be cleared on start", gs.isLevelCleared)
        assertEquals("Player HP must be max on reset", gs.maxHp, gs.hp)
    }
    
    @Test
    fun testCollisionAntiStuckLogic() {
        val gs = GameState()
        gs.gridMap = Array(10) { IntArray(10) { 1 } } // All Walls
        gs.gridMap!![5][5] = 0 // Only one walkable tile
        gs.tileSize = 100f
        gs.mapWidth = 1000f
        gs.mapHeight = 1000f
        
        // Place player in a wall at (1,1)
        gs.px = 150f
        gs.py = 150f
        
        // Run movement update
        gs.updatePlayerMovement(0.016f, 1080f, 2400f, 1000f)
        
        // Current logic doesn't automatically eject unless moved. 
        // But our EnemySystem has anti-stuck. Let's verify coordinates are clamped.
        gs.px = -50f
        gs.py = -50f
        gs.updatePlayerMovement(0.016f, 1080f, 2400f, 1000f)
        
        assertTrue("Player must be clamped to map boundaries (X)", gs.px >= 0f)
        assertTrue("Player must be clamped to map boundaries (Y)", gs.py >= 0f)
    }

    @Test
    fun testSpatialRelativeConsistency() {
        val gs = GameState()
        val screenW = 1080f
        val screenH = 1920f
        
        // 1. Map vs TileSize Consistency
        val cols = 20
        val rows = 30
        gs.tileSize = 100f
        gs.mapWidth = cols * gs.tileSize
        gs.mapHeight = rows * gs.tileSize
        
        assertEquals("Map width must be total of tiles", 2000f, gs.mapWidth, 0.01f)
        assertEquals("Map height must be total of tiles", 3000f, gs.mapHeight, 0.01f)

        // 2. Zoom vs Viewport Relationship
        gs.cameraZoom = 1.0f
        assertEquals("At 1x zoom, viewport should match screen", screenW, gs.getViewportW(screenW, screenH), 0.01f)
        
        gs.cameraZoom = 2.0f
        assertEquals("At 2x zoom, viewport should be half the screen", screenW / 2f, gs.getViewportW(screenW, screenH), 0.01f)
        assertEquals("At 2x zoom, viewport height should be half", screenH / 2f, gs.getViewportH(screenW, screenH), 0.01f)

        // 3. Camera Centering Math
        gs.px = 1000f
        gs.py = 1000f
        gs.camLeadX = 0f
        gs.camLeadY = 0f
        gs.lastFacingX = 0f // Disable lead for perfect centering test
        gs.lastFacingY = 0f
        
        // Manual camera update logic simulation
        val viewportW = gs.getViewportW(screenW, screenH)
        val viewportH = gs.getViewportH(screenW, screenH)
        val expectedCamX = gs.px - viewportW / 2f
        val expectedCamY = gs.py - viewportH / 2f
        
        // Pass baseZoom = 2.0 to keep it from lerping back to 1.0
        repeat(20) { gs.updateCameraLogic(0.1f, screenW, screenH, baseZoom = 2.0f) }
        
        // Note: Boundary clamping might affect this, but at (1000,1000) in a (2000,3000) map, it's safe.
        assertEquals("Camera must center on player X", expectedCamX, gs.cameraX, 5.0f) // Tolerance for lerping
        assertEquals("Camera must center on player Y", expectedCamY, gs.cameraY, 5.0f)

        // 4. Object Screen-Relative Position
        // In the renderer, an object at (gs.px, gs.py) is drawn at:
        // (px - cameraX) * zoom
        // If camera is centered, this should always be (screenW / 2)
        val screenPosX = (gs.px - gs.cameraX) * gs.cameraZoom
        val screenPosY = (gs.py - gs.cameraY) * gs.cameraZoom
        
        assertEquals("Centered player must appear at screen center X", screenW / 2f, screenPosX, 5.0f)
        assertEquals("Centered player must appear at screen center Y", screenH / 2f, screenPosY, 5.0f)
    }

    @Test
    fun testZoomDoesNotAffectWorldCoordinates() {
        val gs = GameState()
        val enemySys = com.appsbyalok.echohunter.systems.EnemySystem()
        
        // 1. Setup World with all objects
        gs.px = 500f
        gs.py = 500f
        
        // Setup Grid (Walls)
        gs.gridMap = Array(10) { IntArray(10) { 0 } }
        gs.tileSize = 100f
        
        // Setup Enemies
        enemySys.ex[0] = 100f
        enemySys.ey[0] = 100f
        
        // Setup Powerups
        enemySys.pwX[0] = 200f
        enemySys.pwY[0] = 200f
        enemySys.pwActive[0] = true
        
        // Setup Compilers (Spawner Nodes)
        gs.spawnerNodes.add(com.appsbyalok.echohunter.systems.SpawnNode(300f, 300f, 0))

        // Record Initial State
        val initPlayer = Pair(gs.px, gs.py)
        val initEnemy = Pair(enemySys.ex[0], enemySys.ey[0])
        val initPowerup = Pair(enemySys.pwX[0], enemySys.pwY[0])
        val initCompiler = Pair(gs.spawnerNodes[0].x, gs.spawnerNodes[0].y)
        val initTileSize = gs.tileSize

        // 2. Perform Extreme Cinematic & Zoom Operations
        gs.cameraZoom = 1.0f
        gs.targetZoom = 10.0f // Mega Zoom In
        gs.cameraFocusWeight = 1.0f
        gs.triggerCinematicFocus(800f, 800f, zoom = 5.0f, duration = 2.0f)
        gs.shakeAmount = 200f
        
        // Run camera logic for many frames
        repeat(1000) {
            gs.updateCameraLogic(0.016f, 1080f, 2400f)
            gs.updateTimers(0.016f, 1080f)
        }
        
        // 3. VERIFICATION: Nothing should have moved in the World space
        assertEquals("Player position must be immutable by camera", initPlayer.first, gs.px, 0.001f)
        assertEquals("Enemy position must be immutable by camera", initEnemy.first, enemySys.ex[0], 0.001f)
        assertEquals("Powerup position must be immutable by camera", initPowerup.first, enemySys.pwX[0], 0.001f)
        assertEquals("Compiler position must be immutable by camera", initCompiler.first, gs.spawnerNodes[0].x, 0.001f)
        assertEquals("Tile size (Walls) must not change", initTileSize, gs.tileSize, 0.001f)
        
        // Zoom out test
        gs.targetZoom = 0.1f
        repeat(50) { gs.updateCameraLogic(0.016f, 1080f, 2400f) }
        
        assertEquals("Player pos must remain stable after zoom out", initPlayer.first, gs.px, 0.001f)
        assertEquals("Compiler pos must remain stable after zoom out", initCompiler.first, gs.spawnerNodes[0].x, 0.001f)
    }
}
