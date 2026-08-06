package com.appsbyalok.echohunter.data

import kotlin.math.pow

// Represents individual gameplay components that can overlap cleanly
enum class LevelFeature(val id: Int) {
    CLASSIC(0),       // Baseline Default: Score/Data collect to win
    MAZE(1),          // Structural Variation: Tight/Complex path layout (% 6)
    DARKNESS(2),      // Environmental Modifier: Lights out, Sonar needed (% 8)
    BOSS(3),          // Prime 5: Warden Arena entity encounter
    ELIMINATION(4),   // Prime 7: Terminate high-value target security arrays
    ESCAPE(5),        // Prime 11: Secure target threshold then locate escape gate
    DEFENSE(6),       // Prime 13: Protect the central core protocol
    BOMB(7),          // Prime 17: Plant a Logic Bomb and defend it
    CLEAN_SWEEP(8),   // Prime 19: Destroy all security compilers (spawners)
    SPECIAL(9),       // Narrative anomaly elements
    ADMIN_BONUS(10);  // Level 100 Easter Egg override

    companion object {
        fun fromId(id: Int): LevelFeature? = entries.find { it.id == id }
    }
}

data class LevelConfig(
    val features: Set<LevelFeature>,
    val targetScore: Long,
    val speedMultiplier: Float,
    val hpMultiplier: Float,
    val spawnRateMultiplier: Float,
    val aiIntelligence: Float,
    val clearRewardKB: Long,
    val parTime: Float
)

object LevelEngine {

    /**
     * Saturated Growth Curve Utility
     * Formula: Base + (MaxAdd * Level) / (Level + K)
     */
    fun getSaturatedValue(level: Int, base: Float, maxAdd: Float, curveConstant: Float): Float {
        val l = level.toFloat()
        return base + (maxAdd * l) / (l + curveConstant)
    }

    /**
     * Algorithmic Generation using Prime (Objectives) and Even (Modifiers) Matrix.
     * Returns a bitmask of LevelFeature ordinals.
     */
    fun getFeaturesMask(level: Int): Int {
        if (level % 100 == 0) return (1 shl LevelFeature.ADMIN_BONUS.id) or (1 shl LevelFeature.BOSS.id)
        if (level == 1) return (1 shl LevelFeature.CLASSIC.id)

        var mask = 0
        if (level % 5 == 0)  mask = mask or (1 shl LevelFeature.BOSS.id)
        if (level % 7 == 0)  mask = mask or (1 shl LevelFeature.ELIMINATION.id)
        if (level % 11 == 0) mask = mask or (1 shl LevelFeature.ESCAPE.id)
        if (level % 13 == 0) mask = mask or (1 shl LevelFeature.DEFENSE.id)
        if (level % 17 == 0) mask = mask or (1 shl LevelFeature.BOMB.id)
        if (level % 19 == 0) mask = mask or (1 shl LevelFeature.CLEAN_SWEEP.id)

        if (level % 6 == 0) mask = mask or (1 shl LevelFeature.MAZE.id)
        if (level % 8 == 0) mask = mask or (1 shl LevelFeature.DARKNESS.id)

        val primaryMask = (1 shl LevelFeature.BOSS.id) or
                (1 shl LevelFeature.ELIMINATION.id) or
                (1 shl LevelFeature.ESCAPE.id) or
                (1 shl LevelFeature.DEFENSE.id) or
                (1 shl LevelFeature.BOMB.id) or
                (1 shl LevelFeature.CLEAN_SWEEP.id)

        if ((mask and primaryMask) == 0) {
            mask = mask or (1 shl LevelFeature.CLASSIC.id)
        }
        return mask
    }

    /**
     * Algorithmic Generation using Prime (Objectives) and Even (Modifiers) Matrix.
     */
    fun determineLevelFeatures(level: Int): Set<LevelFeature> {
        val mask = getFeaturesMask(level)
        val features = mutableSetOf<LevelFeature>()
        LevelFeature.entries.forEach { 
            if ((mask and (1 shl it.id)) != 0) features.add(it)
        }
        return features
    }

    fun getLevelConfig(level: Int, difficulty: Int = 0): LevelConfig {
        val features = determineLevelFeatures(level)

        // --- DIFFICULTY-BASED SCALING ---
        val isHard = difficulty == 1
        
        // Speed: Normal is slow (max 1.4x), Hard is fast (max 2.0x)
        val speedBase = if (isHard) 1.1f else 1.0f
        val speedMax = if (isHard) 0.9f else 0.4f
        val speedMult = getSaturatedValue(level, speedBase, speedMax, if (isHard) 200f else 400f)

        // HP: Normal (max 8x), Hard (max 20x)
        val hpMax = if (isHard) 19.0f else 7.0f
        val hpMult = getSaturatedValue(level, 1.0f, hpMax, 300f)

        // Spawn Rate: Hard has much more enemies
        val spawnMax = if (isHard) 3.0f else 1.2f
        val spawnRateMult = getSaturatedValue(level, 1.0f, spawnMax, 300f)

        // AI Intelligence: Normal stays "dumb" longer
        val aiMax = if (isHard) 0.9f else 0.5f
        val aiIntel = getSaturatedValue(level, 0.15f, aiMax, if (isHard) 80f else 250f)

        val targetScore = (getSaturatedValue(level, 50f, 1950f, 400f) * (1.0f + UpgradeSystem.getRewardBonusPercent())).toLong()

        // --- SMOOTH REWARD SCALING (Prevents Long Overflow) ---
        val baseClear = 100f
        val maxAddClear = 50000f // Base plateau at 50k KB
        var clearReward = getSaturatedValue(level, baseClear, maxAddClear, 300f).toLong()

        // --- ECONOMY BRIDGE: LATE-GAME HYPER-SCALING ---
        // At Level 50+, rewards start scaling exponentially to reach TB-scale eventually.
        if (level > 50) {
            // Cap the exponent input to prevent Double.POSITIVE_INFINITY and subsequent Long overflow
            val hyperLevel = (level - 50.0).coerceAtMost(1000.0)
            val hyperFactor = 1.15.pow(hyperLevel) 
            
            // Perform multiplication in Double to detect overflow before converting to Long
            val scaledReward = clearReward.toDouble() * hyperFactor
            clearReward = if (scaledReward > Long.MAX_VALUE) Long.MAX_VALUE else scaledReward.toLong()
        }
        // Cap it at 1000 TB to prevent overflow and maintain economy balance
        clearReward = clearReward.coerceAtMost(1000L * 1024L * 1024L * 1024L)

        var featureMult = 1.0
        if (features.contains(LevelFeature.BOSS)) featureMult *= 2.5
        if (features.contains(LevelFeature.DEFENSE)) featureMult *= 1.5
        if (features.contains(LevelFeature.ESCAPE)) featureMult *= 1.3
        if (features.contains(LevelFeature.BOMB)) featureMult *= 1.8 // Bomb reward modifier
        if (features.contains(LevelFeature.CLEAN_SWEEP)) featureMult *= 2.0 // High reward for total destruction
        if (features.contains(LevelFeature.DARKNESS)) featureMult *= 1.2 // Bonus for playing in the dark

        clearReward = (clearReward * featureMult).toLong()
        clearReward += (clearReward * UpgradeSystem.getRewardBonusPercent()).toLong()

        if (features.contains(LevelFeature.ADMIN_BONUS)) {
            return LevelConfig(features, 0, 1.1f, 1f, 0.4f, 0f, 153600L, 180f)
        }

        // --- DYNAMIC PAR TIME CALCULATION ---
        val basePar = if (isHard) 50f else 60f
        // Level growth: up to 120s extra for higher levels
        val levelTimeGrowth = getSaturatedValue(level, 0f, 120f, 600f)
        
        var featureBonus = 0f
        if (features.contains(LevelFeature.MAZE)) featureBonus += 25f
        if (features.contains(LevelFeature.BOSS)) {
            // Boss HP scales massively, so time also scales
            featureBonus += 30f + getSaturatedValue(level, 0f, 90f, 400f)
        }
        if (features.contains(LevelFeature.DEFENSE)) {
            // Defense is wave-based, requires more time for enemy spawns
            featureBonus += 80f + (level / 25f)
        }
        if (features.contains(LevelFeature.BOMB)) featureBonus += 55f
        if (features.contains(LevelFeature.CLEAN_SWEEP)) featureBonus += 45f
        if (features.contains(LevelFeature.ESCAPE)) featureBonus += 35f
        if (features.contains(LevelFeature.DARKNESS)) featureBonus += 20f
        
        var parTime = basePar + levelTimeGrowth + featureBonus
        // Hard mode is 10% tighter for a challenge
        if (isHard) parTime *= 0.9f

        return LevelConfig(
            features = features,
            targetScore = targetScore,
            speedMultiplier = speedMult,
            hpMultiplier = hpMult,
            spawnRateMultiplier = spawnRateMult,
            aiIntelligence = aiIntel,
            clearRewardKB = clearReward,
            parTime = parTime
        )
    }

    fun getKillRewardKB(level: Int, isBoss: Boolean): Long {
        var base = if (isBoss) {
            getSaturatedValue(level, 400f, 1600f, 200f).toLong() // Max 2000 KB for Boss
        } else {
            getSaturatedValue(level, 5f, 45f, 300f).toLong() // Max 50 KB for Normal
        }

        // --- ECONOMY BRIDGE: KILL SCALING ---
        if (level > 50) {
            val hyperLevel = (level - 50.0).coerceAtMost(1000.0)
            val hyperFactor = 1.12.pow(hyperLevel)
            val scaledBase = base.toDouble() * hyperFactor
            base = if (scaledBase > Long.MAX_VALUE) Long.MAX_VALUE else scaledBase.toLong()
        }

        return base + (base * UpgradeSystem.getRewardBonusPercent()).toLong()
    }
}