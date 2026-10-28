package com.luojuly.watchbms

internal const val BURN_RADIUS = 2
internal const val BURN_INTERVAL_MS = 60_000L
private const val BURN_SPAN = BURN_RADIUS * 2 + 1
private const val BURN_STEPS = BURN_SPAN * BURN_SPAN

/** Center of the 5×5 pixel walk, where the page has not moved yet. */
internal fun burnOriginStep(): Int = BURN_STEPS / 2

/** Pixel offset for one step of a boustrophedon inside ±BURN_RADIUS. */
internal fun burnOffset(step: Int): Pair<Int, Int> {
    val row = step / BURN_SPAN
    val col = step % BURN_SPAN
    val x = if (row % 2 == 0) col - BURN_RADIUS else BURN_RADIUS - col
    val y = row - BURN_RADIUS
    return x to y
}

/** Next step and direction. The walk reverses at either end so it never jumps. */
internal fun nextBurnStep(step: Int, direction: Int): Pair<Int, Int> {
    val dir = if (step + direction in 0 until BURN_STEPS) direction else -direction
    return (step + dir) to dir
}
