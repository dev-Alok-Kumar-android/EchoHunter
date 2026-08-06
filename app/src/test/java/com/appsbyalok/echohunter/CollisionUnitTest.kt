package com.appsbyalok.echohunter

import com.appsbyalok.echohunter.engine.GameModeId
import com.appsbyalok.echohunter.engine.GameState
import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionUnitTest {

    @Test
    fun testPlayerSlidingOnWall() {
        // 1. Setup GameState
        val gs = GameState()
        gs.tileSize = 100f
        gs.gameMode = GameModeId.CAMPAIGN 

        // 2. Create a Mock Grid (0 = Path, 1 = Wall)
        gs.gridMap = arrayOf(
            intArrayOf(1, 1, 1, 1),
            intArrayOf(1, 0, 0, 1), 
            intArrayOf(1, 0, 0, 1),
            intArrayOf(1, 1, 1, 1)
        )

        gs.mapWidth = 400f
        gs.mapHeight = 400f

        // 3. Place player right next to the right wall (Tile size 100, wall at index 3)
        gs.px = 280f
        gs.py = 150f

        val initialY = gs.py

        // 4. Force movement Diagonally into the wall (Right + Down)
        gs.controls.moveDirX = 1f  
        gs.controls.moveDirY = 1f  

        val dt = 0.016f
        val scale = 1000f

        repeat(10) {
            gs.updatePlayerMovement(dt, 1080f, 1920f, scale)
        }

        // 5. ASSERTIONS
        // Check X: Player should NOT cross into the wall
        assertTrue("Player crossed the wall! X is ${gs.px}", gs.px < 300f)

        // Check Y: Player SHOULD slide down successfully
        assertTrue("Player did not slide on Y axis! Y is ${gs.py}", gs.py > initialY)
    }
}
