package com.cerniera.curva

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One Arsenal match, from Arsenal's side. */
data class Match(
    val id: Long,
    val at: Instant,
    val status: String,           // SCHEDULED, TIMED, IN_PLAY, PAUSED, FINISHED, POSTPONED…
    val competition: String,
    val home: Boolean,
    val opponent: String,
    val opponentShort: String,
    val ft: Pair<Int, Int>?,      // ours to theirs
    val ht: Pair<Int, Int>?,
    val goals: List<Goal>?,       // null when the plan doesn't give goal times
    val minute: Int? = null,
) {
    val finished get() = status == "FINISHED"
    val live get() = status == "IN_PLAY" || status == "PAUSED"
    val upcoming get() = status == "SCHEDULED" || status == "TIMED"
    val shape get() = Curve.of(goals, ht, ft)

    fun local(): ZonedDateTime = at.atZone(UK)
    fun day(): String = local().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
    fun time(): String = local().format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
    fun venue() = if (home) "Home" else "Away"
    fun score() = ft?.let { "${it.first}–${it.second}" } ?: "–"

    companion object { val UK: ZoneId = ZoneId.of("Europe/London") }
}

/**
 * football-data.org (v4), with the key Mechack enters in settings. The free plan gives 10 calls a minute
 * with scores and schedules delayed; live scores start at a paid plan. Everything read is kept in one
 * file, so Curva opens instantly and calls the API only when the copy is old.
 */
object Data {
    private const val TAG = "Curva"
    private const val BASE = "https://api.football-data.org/v4"
    private const val TLA = "ARS"

    private fun prefs(c: Context) = c.getSharedPreferences("curva", Context.MODE_PRIVATE)
    fun key(c: Context) = prefs(c).getString("key", "")!!.trim()
    fun setKey(c: Context, k: String) = prefs(c).edit().putString("key", k.trim()).remove("team").apply()
    fun demo(c: Context) = prefs(c).getBoolean("demo", false)
    fun setDemo(c: Context, on: Boolean) = prefs(c).edit().putBoolean("demo", on).apply()
    fun channel(c: Context, id: Long) = prefs(c).getString("ch_$id", null)
    fun setChannel(c: Context, id: Long, ch: String?) = prefs(c).edit().apply { if (ch == null) remove("ch_$id") else putString("ch_$id", ch) }.apply()
    private fun cache(c: Context) = File(c.filesDir, "matches.json")
    fun fetchedAt(c: Context) = prefs(c).getLong("fetched", 0L)

    class Problem(message: String) : Exception(message)

    private fun get(c: Context, path: String): JSONObject {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.setRequestProperty("X-Auth-Token", key(c))
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        Log.i(TAG, "GET $path → $code")
        when (code) {
            in 200..299 -> return JSONObject(body)
            400, 401 -> throw Problem("football-data.org didn't accept the key. Check it in settings")
            403 -> throw Problem("Your football-data.org plan doesn't include this")
            429 -> throw Problem("Too many requests for the free plan. Try again in a minute")
            else -> throw Problem("football-data.org answered $code")
        }
    }

    /** Arsenal's id, found once from the Premier League's teams by its three letters. */
    private fun team(c: Context): Long {
        prefs(c).getLong("team", 0L).takeIf { it > 0 }?.let { return it }
        val teams = get(c, "/competitions/PL/teams").getJSONArray("teams")
        for (i in 0 until teams.length()) {
            val t = teams.getJSONObject(i)
            if (t.optString("tla") == TLA) { val id = t.getLong("id"); prefs(c).edit().putLong("team", id).apply(); return id }
        }
        throw Problem("Arsenal wasn't found among the Premier League's teams")
    }

    /** Reads the season's matches, then the goal times of the last five (where the plan gives them). */
    fun refresh(c: Context): List<Match> {
        if (key(c).isEmpty()) throw Problem("Add your football-data.org key in settings")
        val id = team(c)
        val list = get(c, "/teams/$id/matches").getJSONArray("matches")
        // The last five's details, for goal times: five calls, inside the free plan's ten a minute
        val finished = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("status") == "FINISHED" }
            .sortedBy { it.optString("utcDate") }.takeLast(5)
        val details = JSONObject()
        for (m in finished) runCatching { details.put(m.getLong("id").toString(), get(c, "/matches/${m.getLong("id")}")) }
            .onFailure { Log.w(TAG, "detail ${m.optLong("id")}: $it") }
        val all = JSONObject().put("team", id).put("matches", list).put("details", details)
        cache(c).writeText(all.toString())
        prefs(c).edit().putLong("fetched", System.currentTimeMillis()).apply()
        return parse(all)
    }

    fun cached(c: Context): List<Match> = if (demo(c)) Demo.matches() else
        runCatching { parse(JSONObject(cache(c).readText())) }.getOrDefault(emptyList())

    private fun pair(o: JSONObject?, home: Boolean): Pair<Int, Int>? {
        if (o == null || o.isNull("home") || o.isNull("away")) return null
        return Curve.ours(o.getInt("home"), o.getInt("away"), home)
    }

    fun parse(all: JSONObject): List<Match> {
        val us = all.getLong("team")
        val list = all.getJSONArray("matches")
        val details = all.optJSONObject("details") ?: JSONObject()
        return (0 until list.length()).map { i ->
            val m = list.getJSONObject(i)
            val homeTeam = m.getJSONObject("homeTeam"); val awayTeam = m.getJSONObject("awayTeam")
            val home = homeTeam.optLong("id") == us
            val them = if (home) awayTeam else homeTeam
            val score = m.optJSONObject("score")
            val detail = details.optJSONObject(m.getLong("id").toString())
            Match(
                id = m.getLong("id"),
                at = Instant.parse(m.getString("utcDate")),
                status = m.optString("status"),
                competition = m.optJSONObject("competition")?.optString("name").orEmpty(),
                home = home,
                opponent = them.optString("shortName").ifEmpty { them.optString("name") },
                opponentShort = them.optString("tla"),
                ft = pair(score?.optJSONObject("fullTime"), home),
                ht = pair(score?.optJSONObject("halfTime"), home),
                goals = goals(detail?.optJSONArray("goals"), us),
                minute = m.optInt("minute", -1).takeIf { it >= 0 },
            )
        }.sortedBy { it.at }
    }

    private fun goals(a: JSONArray?, us: Long): List<Goal>? {
        if (a == null || a.length() == 0) return null
        return (0 until a.length()).map { i ->
            val g = a.getJSONObject(i)
            val own = g.optString("type") == "OWN"
            val team = g.optJSONObject("team")?.optLong("id")
            // An own goal is listed under the side that scored it against itself
            val forUs = (team == us) != own
            Goal(g.optInt("minute"), forUs, g.optJSONObject("scorer")?.optString("name").orEmpty())
        }
    }
}

/**
 * For the emulator and for trying Curva before a key is added: an invented run of fixtures, clearly
 * marked as demo data in the app. None of these are real results.
 */
object Demo {
    fun matches(): List<Match> {
        val now = ZonedDateTime.now(Match.UK).withHour(15).withMinute(0).withSecond(0).withNano(0)
        fun at(days: Long, h: Int = 15, m: Int = 0) = now.plusDays(days).withHour(h).withMinute(m).toInstant()
        fun done(id: Long, d: Long, opp: String, tla: String, home: Boolean, goals: List<Goal>, comp: String = "Premier League"): Match {
            val f = goals.count { it.ours } to goals.count { !it.ours }
            val h = goals.filter { it.minute <= 45 }.let { g -> g.count { it.ours } to g.count { !it.ours } }
            return Match(id, at(d), "FINISHED", comp, home, opp, tla, f, h, goals)
        }
        return listOf(
            done(1, -24, "Rovers", "ROV", true, listOf(Goal(12, true, "Ferri"), Goal(58, true, "Okoro"))),
            done(2, -17, "Harbour", "HAR", false, listOf(Goal(31, false, "Lund"), Goal(77, true, "Ferri"))),
            done(3, -10, "Athletic", "ATH", true, listOf(Goal(9, false, "Mbeki"), Goal(40, true, "Sato"), Goal(63, true, "Okoro"), Goal(88, true, "Ferri"))),
            done(4, -7, "Lions", "LIO", false, listOf(Goal(52, false, "Ruiz")), "Champions League"),
            done(5, -3, "Wanderers", "WAN", true, listOf(Goal(18, true, "Sato"), Goal(36, false, "Hale"), Goal(71, false, "Brook"), Goal(90, true, "Okoro"))),
            Match(6, at(4, 17, 30), "TIMED", "Premier League", false, "Borough", "BOR", null, null, null),
            Match(7, at(8, 20, 0), "TIMED", "Champions League", true, "Meridian", "MER", null, null, null),
            Match(8, at(11, 14, 0), "TIMED", "Premier League", true, "Castle", "CAS", null, null, null),
            Match(9, at(18), "TIMED", "Premier League", false, "Vale", "VAL", null, null, null),
            Match(10, at(25, 16, 30), "TIMED", "Premier League", true, "Mariners", "MAR", null, null, null),
        )
    }
}
