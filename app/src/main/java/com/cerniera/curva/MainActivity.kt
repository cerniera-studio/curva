package com.cerniera.curva

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cerniera.kit.Card
import com.cerniera.kit.Chips
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.Pill
import com.cerniera.kit.Row
import com.cerniera.kit.Sheet
import com.cerniera.kit.Toggle
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.endValue
import com.cerniera.kit.eyebrow
import com.cerniera.kit.field
import com.cerniera.kit.pressable
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import com.cerniera.kit.withAlpha
import java.time.Duration
import java.time.Instant

/**
 * Curva: Arsenal at a glance. When, where, and what the score did, without opening three apps.
 *
 * Shapes (the family rule): the upright cover is one column (the next match, then the last five as
 * small curves, the chosen one opening large in place). Every other shape is two panes: the next
 * match and the list on one side, the chosen match's curve and goals on the other, fitted to the
 * height, so nothing that matters sits below the fold.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var matches: List<Match> = emptyList()
    private var chosen: Long? = null
    private var problem: String? = null
    private var loading = false
    private var insets = intArrayOf(0, 0, 0, 0)

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Curva")) return
        Kit.dark = true
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = 0; window.navigationBarColor = 0
        // For the emulator walk: invented fixtures, marked as such
        if (intent?.getStringExtra("seed") == "demo") Data.setDemo(this, true)
        matches = Data.cached(this)
        render()
        val old = System.currentTimeMillis() - Data.fetchedAt(this) > 10 * 60_000L
        if (!Data.demo(this) && Data.key(this).isNotEmpty() && old) refresh()
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); render() }

    private fun refresh() {
        if (Data.demo(this)) { matches = Data.cached(this); render(); return }
        loading = true; problem = null; render()
        Thread {
            val r = runCatching { Data.refresh(this) }
            main.post {
                loading = false
                r.onSuccess { matches = it }.onFailure { problem = (it as? Data.Problem)?.message ?: "Couldn't reach football-data.org" }
                render()
            }
        }.start()
    }

    // ---- The shapes ----

    private fun render() {
        val cfg = resources.configuration
        val w = cfg.screenWidthDp; val h = cfg.screenHeightDp
        val oneColumn = w < 600 && h > w
        val root = FrameLayout(this).apply { setBackgroundColor(Kit.GROUND) }
        root.setOnApplyWindowInsetsListener { v, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insets = intArrayOf(b.left, b.top, b.right, b.bottom)
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
        }
        val next = matches.firstOrNull { it.live } ?: matches.firstOrNull { it.upcoming && it.at.isAfter(Instant.now().minus(Duration.ofHours(3))) }
        val past = matches.filter { it.finished }.takeLast(5).reversed()
        if (chosen == null || matches.none { it.id == chosen }) chosen = past.firstOrNull()?.id
        val pick = matches.firstOrNull { it.id == chosen }

        if (oneColumn) {
            val col = column()
            col.addView(head())
            notices(col)
            next?.let { col.addView(nextCard(it, compact = false), lp(top = 14)) }
            if (past.isNotEmpty()) col.addView(eyebrow("Last five"), lp(top = 22))
            past.forEach { m ->
                col.addView(resultRow(m), lp(top = 8))
                if (m.id == chosen) col.addView(detail(m, height = 210), lp(top = 4))
            }
            upcoming(col, next)
            col.addView(settingsRow(), lp(top = 20))
            root.addView(scroll(col))
        } else {
            val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = column(side = 18)
            left.addView(head())
            notices(left)
            next?.let { left.addView(nextCard(it, compact = h < 500), lp(top = 12)) }
            if (past.isNotEmpty()) left.addView(eyebrow("Last five"), lp(top = 18))
            past.forEach { left.addView(resultRow(it), lp(top = 8)) }
            upcoming(left, next)
            left.addView(settingsRow(), lp(top = 18))
            panes.addView(scroll(left), LinearLayout.LayoutParams(0, -1, if (w > h) 0.46f else 0.5f))
            // The chosen match fills the other pane, its curve taking the height left over
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(26), px(18), px(16)) }
            if (pick != null) {
                right.addView(detailHead(pick))
                right.addView(CurveView(this, big = true).also { it.match = pick }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(10) })
                right.addView(goalsLine(pick), lp(top = 8))
            } else right.addView(text("Results appear here once a match is played.", 15f, Kit.MUTED))
            panes.addView(right, LinearLayout.LayoutParams(0, -1, if (w > h) 0.54f else 0.5f))
            root.addView(panes)
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun column(side: Int = 20) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(side), px(26), px(side), px(36)) }
    private fun scroll(v: View) = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(v) }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) }

    private fun head(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("ARSENAL AT A GLANCE", 11f, Kit.MUTED, 500).apply { letterSpacing = 0.22f })
        names.addView(text("Curva", 40f, Kit.INK, displayFace = true))
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val pill = Pill(this)
        when {
            Data.demo(this) -> pill.set("Demo", Kit.WARN)
            loading -> pill.set("Reading", Kit.CHAMPAGNE)
            problem != null -> pill.set("Offline", Kit.ROSSO)
            matches.any { it.live } -> pill.set("Live · delayed", Kit.ROSSO)
            else -> pill.set(ago(), Kit.GOOD)
        }
        pill.setOnClickListener { refresh() }
        row.addView(pill)
        return row
    }

    private fun ago(): String {
        val t = Data.fetchedAt(this); if (t == 0L) return "Not read"
        val min = (System.currentTimeMillis() - t) / 60_000
        return if (min < 1) "Just now" else if (min < 60) "$min min ago" else "${min / 60} h ago"
    }

    /** What's missing, said once, at the top: the key, a problem, or that this is demo data. */
    private fun notices(col: LinearLayout) {
        when {
            Data.demo(this) -> col.addView(note("Invented fixtures to show the shapes. None of these are real results. Turn the demo off in settings."), lp(top = 12))
            Data.key(this).isEmpty() -> col.addView(Card(this).apply {
                row(Row(this@MainActivity, "Add your football-data.org key", "A free key gives fixtures and results (scores arrive late). Curva keeps it on the phone", endValue("Add", Kit.CHAMPAGNE)) { settings() })
            }, lp(top = 12))
            problem != null -> col.addView(note(problem!!), lp(top = 12))
        }
    }

    private fun note(s: String) = text(s, 14f, Kit.MUTED).apply {
        setPadding(px(14), px(10), px(14), px(10)); background = rounded(Kit.SURFACE, px(14).toFloat(), Kit.HAIRLINE, px(1))
    }

    private fun nextCard(m: Match, compact: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = rounded(Kit.SURFACE, px(26).toFloat(), withAlpha(Kit.CHAMPAGNE, 0.28f), px(1))
        }
        card.addView(eyebrow(if (m.live) "Now · ${m.minute?.let { "$it'" } ?: "live"}" else "Next · ${m.competition}"))
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        line.addView(text(m.opponent, if (compact) 30f else 38f, Kit.INK, displayFace = true), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text(if (m.live) m.score() else m.time(), if (compact) 30f else 38f, if (m.live) Kit.CHAMPAGNE else Kit.INK, displayFace = true))
        card.addView(line)
        card.addView(text("${m.venue()} · ${until(m)}", 14f, Kit.MUTED).apply { setPadding(0, px(2), 0, 0) })
        val ch = Data.channel(this, m.id)
        val watch = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, 0) }
        watch.addView(text(ch?.let { "On $it" } ?: "Where it's on: set the channel", 15f, if (ch == null) Kit.CHAMPAGNE else Kit.INK, 450).apply {
            pressable(); setOnClickListener { channelSheet(m) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (ch != null && Watch.app(ch) != null) watch.addView(primary("Watch") { Watch.open(this, ch) })
        card.addView(watch)
        return card
    }

    private fun until(m: Match): String {
        val days = Duration.between(Instant.now(), m.at).toHours().let { (it + 12) / 24 }
        val day = when { m.live -> "playing now"; days <= 0L -> "today"; days == 1L -> "tomorrow"; else -> m.day() }
        return if (m.live) day else "$day at ${m.time()}"
    }

    private fun resultRow(m: Match): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(10), px(12), px(10))
            background = rounded(if (m.id == chosen) Kit.RAISED else Kit.SURFACE, px(18).toFloat(), if (m.id == chosen) withAlpha(Kit.CHAMPAGNE, 0.35f) else Kit.HAIRLINE, px(1))
            pressable(); setOnClickListener { chosen = m.id; render() }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(m.opponent, 17f, Kit.INK, 450))
        words.addView(text("${m.venue()} · ${m.day()}", 12.5f, Kit.MUTED))
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(CurveView(this, big = false).also { it.match = m }, LinearLayout.LayoutParams(px(84), px(34)).apply { marginEnd = px(12) })
        val won = m.ft?.let { it.first > it.second } == true
        val lost = m.ft?.let { it.first < it.second } == true
        row.addView(text(m.score(), 22f, if (won) Kit.CHAMPAGNE else if (lost) Kit.ROSSO else Kit.INK, displayFace = true))
        return row
    }

    private fun detailHead(m: Match): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(eyebrow("${m.competition} · ${m.day()}"))
        val ft = m.ft
        addView(text(if (ft != null) "${Curve.verdict(ft)} ${ft.first}–${ft.second} ${if (m.home) "v" else "at"} ${m.opponent}" else m.opponent, 26f, Kit.INK, displayFace = true))
    }

    /** The one-column detail: the curve large, the goals under it. */
    private fun detail(m: Match, height: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(6), px(6), px(6), px(6))
        addView(CurveView(this@MainActivity, big = true).also { it.match = m }, LinearLayout.LayoutParams(-1, px(height)))
        addView(goalsLine(m))
    }

    private fun goalsLine(m: Match): View {
        val g = m.goals
        val s = when {
            g == null -> "Goal times need a paid football-data.org plan, so the line runs through the half-time and full-time scores, dashed."
            g.isEmpty() -> "No goals."
            else -> g.sortedBy { it.minute }.joinToString("   ") { "${it.minute}' ${it.scorer.ifEmpty { if (it.ours) "Arsenal" else m.opponent }}" }
        }
        return text(s, 13.5f, Kit.MUTED).apply { setPadding(px(4), 0, px(4), 0) }
    }

    private fun upcoming(col: LinearLayout, next: Match?) {
        val later = matches.filter { it.upcoming && it.id != next?.id && it.at.isAfter(Instant.now()) }.take(4)
        if (later.isEmpty()) return
        col.addView(eyebrow("Then"), lp(top = 20))
        col.addView(Card(this).apply {
            later.forEach { m -> row(Row(this@MainActivity, m.opponent, "${m.venue()} · ${m.competition}", endValue("${m.day()} ${m.time()}")) { channelSheet(m) }) }
        }, lp(top = 6))
    }

    private fun settingsRow() = text("Settings", 15f, Kit.MUTED, 450).apply {
        gravity = Gravity.CENTER; setPadding(0, px(12), 0, px(12)); pressable(); setOnClickListener { settings() }
    }

    // ---- Sheets ----

    private fun channelSheet(m: Match) {
        Sheet.show(this, m.opponent) { sheet ->
            sheet.body.addView(text("${m.venue()} · ${m.day()} at ${m.time()} · ${m.competition}", 14f, Kit.MUTED))
            sheet.body.addView(eyebrow("Where it's on"), lp(top = 16))
            val options = Watch.CHANNELS + "Not on TV"
            val now = Data.channel(this, m.id)
            sheet.body.addView(Chips(this, options, options.indexOf(now ?: "")) { i ->
                Data.setChannel(this, m.id, options[i].takeIf { it != "Not on TV" }); sheet.dismiss(); render()
            }, lp(top = 8))
            sheet.body.addView(text("UK broadcast picks have no public feed, so you set them. A channel with an app gets a Watch button.", 13f, Kit.MUTED), lp(top = 14))
        }
    }

    private fun settings() {
        Sheet.show(this, "Settings", onClose = { render() }) { sheet ->
            val b = sheet.body
            b.addView(eyebrow("football-data.org"))
            val key = field("Your API key", Data.key(this))
            b.addView(key, lp(top = 8))
            b.addView(primary("Save and read") { Data.setKey(this, key.text.toString()); Data.setDemo(this, false); sheet.dismiss(); refresh() }, lp(top = 10))
            b.addView(Card(this).apply {
                row(Row(this@MainActivity, "Get a free key", "Register at football-data.org; the key arrives by email", endValue("›")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.football-data.org/client/register"))) }
                })
                row(Row(this@MainActivity, "Demo fixtures", "Invented matches to see the shapes before a key is added", Toggle(this@MainActivity, Data.demo(this@MainActivity)) { on ->
                    Data.setDemo(this@MainActivity, on); matches = Data.cached(this@MainActivity)
                }))
            }, lp(top = 14))
            b.addView(text("The free plan gives fixtures and results, with scores and schedules arriving late, and allows ten requests a minute. Live scores and goal times need a paid plan. Curva reads at most every ten minutes, or when you tap the status.", 13f, Kit.MUTED), lp(top = 14))
            b.addView(doctrineCard(listOf(
                "Membrane" to "Each match is one line from kick-off to the whistle. A goal pulls it up or down, and everything after moves with it.",
                "Concentrato" to "On the list each result is reduced to its shape: a season reads as a run of curves.",
                "Giulietta" to "The goals wait in the trim under the curve: touch the line and the nearest one rises, then sinks back.")), lp(top = 16))
        }
    }
}

/** The channels you can choose, and the app that opens each (where one exists on the phone). */
object Watch {
    val CHANNELS = listOf("Sky Sports", "TNT Sports", "Prime Video", "BBC", "ITV")
    fun app(ch: String): String? = when (ch) {
        "Sky Sports" -> "com.bskyb.skygo"
        "Prime Video" -> "com.amazon.avod.thirdpartyclient"
        "BBC" -> "bbc.iplayer.android"
        "ITV" -> "air.ITVMobilePlayer"
        else -> null
    }
    fun open(a: Activity, ch: String) {
        val pkg = app(ch) ?: return
        val i = a.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        runCatching { a.startActivity(i) }
    }
}
