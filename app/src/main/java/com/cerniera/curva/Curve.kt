package com.cerniera.curva

/**
 * The shape of a match, as Arsenal saw it: the goal difference through the game. Pure Kotlin, so it is
 * tested on the JVM.
 *
 * With goal times (football-data.org's paid tiers, or the demo), each goal bends the line over a couple
 * of minutes, as a pull on a membrane. With only the half-time and full-time scores (the free plan),
 * the line can only pass through those two points, and [exact] is false so it is drawn dashed.
 */
data class Goal(val minute: Int, val ours: Boolean, val scorer: String = "", val red: Boolean = false)

data class Shape(val points: List<Pair<Float, Float>>, val exact: Boolean, val end: Float, val low: Int, val high: Int)

object Curve {
    /** How long a goal takes to bend the line, in match minutes. */
    private const val BEND = 2.5f

    fun of(goals: List<Goal>?, ht: Pair<Int, Int>?, ft: Pair<Int, Int>?, length: Int = 90): Shape {
        val scoring = goals?.filter { !it.red }
        val end = maxOf(length.toFloat(), (scoring?.maxOfOrNull { it.minute } ?: 0).toFloat() + 2f)
        if (!scoring.isNullOrEmpty() || (goals != null && ft != null && ft.first + ft.second == 0)) {
            val sorted = scoring.orEmpty().sortedBy { it.minute }
            val pts = mutableListOf(0f to 0f)
            var d = 0
            for (g in sorted) {
                val m = g.minute.toFloat().coerceAtMost(end)
                pts += m to d.toFloat()
                d += if (g.ours) 1 else -1
                pts += (m + BEND).coerceAtMost(end) to d.toFloat()
            }
            pts += end to d.toFloat()
            return Shape(pts, true, end, minOf(0, pts.minOf { it.second }.toInt()), maxOf(0, pts.maxOf { it.second }.toInt()))
        }
        if (ft == null) return Shape(listOf(0f to 0f), false, end, 0, 0)
        val h = ht?.let { (it.first - it.second).toFloat() } ?: (ft.first - ft.second) / 2f
        val f = (ft.first - ft.second).toFloat()
        val pts = listOf(0f to 0f, 45f to h, end to f)
        return Shape(pts, false, end, minOf(0, minOf(h, f).toInt()), maxOf(0, maxOf(h, f).toInt()))
    }

    /** Arsenal's side of a score, given who was at home. */
    fun ours(home: Int, away: Int, atHome: Boolean) = if (atHome) home to away else away to home

    /** The word for a result: Won, Drew, Lost. */
    fun verdict(ft: Pair<Int, Int>) = when { ft.first > ft.second -> "Won"; ft.first < ft.second -> "Lost"; else -> "Drew" }
}
