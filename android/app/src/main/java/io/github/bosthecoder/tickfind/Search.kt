package io.github.bosthecoder.tickfind

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One open task or note, as cached from TickTick. [content] holds the note text (or a checklist's description). Times are epoch ms. */
data class TTask(
    val id: String, val projectId: String, val title: String, val content: String, val kind: String, val priority: Int,
    val tags: List<String>, val items: List<String>, val due: Long?, val modified: Long, val created: Long, val list: String,
) {
    /** Lower-cased, accent-free copies of the searchable fields, built once. */
    internal val n by lazy { Norm(this) }
}

internal class Norm(t: TTask) {
    val title = Search.fold(t.title)
    val body = Search.fold(t.content + "\n" + t.items.joinToString("\n"))
    val list = Search.fold(t.list)
    val tags = t.tags.map(Search::fold)
}

enum class Sort { Priority, Best, Due, Modified, Created }

data class Hit(val task: TTask, val score: Int)

/** What was typed, split into words to match and filters. */
class Query {
    val terms = mutableListOf<String>(); val not = mutableListOf<String>(); val tags = mutableListOf<String>()
    val lists = mutableListOf<String>(); val notLists = mutableListOf<String>()
    val priorities = mutableSetOf<Int>()
    val due = mutableSetOf<String>()   // any of these
    var kind: String? = null; var sort: Sort? = null
}

/**
 * Local search over the cached tasks, the same rules as the Windows app's Search.cs: every word must match somewhere
 * (title, tags, list, notes, checklist), title hits score highest, and only when nothing matches exactly is one typo per word allowed.
 */
object Search {
    private val marks = Regex("\\p{Mn}+")
    fun fold(s: String): String = marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

    private val priorityWords = mapOf(
        "high" to 5, "h" to 5, "5" to 5, "medium" to 3, "med" to 3, "m" to 3, "3" to 3,
        "low" to 1, "l" to 1, "1" to 1, "none" to 0, "n" to 0, "0" to 0,
    )

    /** Everything typed, every side of a "|" together (for highlighting the words). */
    fun parse(text: String): Query {
        val q = Query()
        for (raw in tokens(text)) if (raw != "|") add(q, raw)
        return q
    }

    /** One query per side of each "|", each with [filter]'s tokens first. Empty sides (a "|" typed but nothing after it) are dropped. */
    private fun alternatives(text: String, filter: String): List<Query> {
        val sides = mutableListOf(mutableListOf<String>())
        for (raw in tokens(text)) if (raw == "|") sides += mutableListOf<String>() else sides.last() += raw
        val f = tokens(filter)
        return sides.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList<String>()) }.map { side -> Query().also { q -> (f + side).forEach { add(q, it) } } }
    }

    private fun add(q: Query, raw: String) {
        val w = fold(raw)
        when {
            w.length > 1 && w[0] == '#' -> q.tags += w.substring(1)
            w.length > 1 && w[0] == '~' -> q.lists += w.substring(1)
            w.startsWith("list:") && w.length > 5 -> q.lists += w.substring(5)
            w.length > 2 && w.startsWith("-~") -> q.notLists += w.substring(2)
            w.length > 1 && w[0] == '!' && priorityWords.containsKey(w.substring(1)) -> q.priorities += priorityWords.getValue(w.substring(1))
            w.startsWith("is:") && w.length > 3 -> q.kind = w.substring(3)
            w.startsWith("due:") && w.length > 4 -> q.due += w.substring(4)
            w == "has:due" -> q.due += "any"
            w.startsWith("sort:") && sortOf(w.substring(5)) != null -> q.sort = sortOf(w.substring(5))
            w.length > 1 && w[0] == '-' -> q.not += w.substring(1)
            w.isNotEmpty() -> q.terms += w
        }
    }

    fun sortOf(s: String): Sort? = if (s == "best" || s == "relevance") Sort.Best else Sort.entries.firstOrNull { it.name.equals(s, true) }

    /** Splits on spaces, keeping "quoted phrases" together. */
    private fun tokens(text: String): List<String> {
        val out = mutableListOf<String>(); val b = StringBuilder(); var quoted = false
        for (c in text) {
            if (c == '"') { quoted = !quoted; continue }
            if (c.isWhitespace() && !quoted) { if (b.isNotEmpty()) out += b.toString(); b.clear() } else b.append(c)
        }
        if (b.isNotEmpty()) out += b.toString()
        return out
    }

    /** [text] may hold "a | b" (either side); [filter] (the default filter) applies to every side. */
    fun run(tasks: List<TTask>, text: String, defaultSort: Sort, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), filter: String = ""): List<Hit> {
        val alts = alternatives(text, filter)
        var hits = match(tasks, alts, today, zone, fuzzy = false)
        if (hits.isEmpty() && alts.any { it.terms.isNotEmpty() }) hits = match(tasks, alts, today, zone, fuzzy = true)
        return order(hits, alts.firstNotNullOfOrNull { it.sort } ?: defaultSort)
    }

    /** "is:list" asks for lists rather than tasks. */
    fun wantsLists(text: String) = parse(text).kind?.startsWith("list") == true

    /** The lists holding [tasks], with how many each, most first. Every plain word in [text] has to be in the list's name. */
    fun lists(tasks: List<TTask>, text: String): List<Pair<String, Int>> {
        val words = parse(text).terms
        return tasks.groupBy { it.list }.filter { (_, g) -> words.all { it in g[0].n.list } }
            .map { (name, g) -> name to g.size }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    /** Each task that some side lets through, scored by its best side. */
    private fun match(tasks: List<TTask>, alts: List<Query>, today: LocalDate, zone: ZoneId, fuzzy: Boolean) =
        tasks.mapNotNull { t -> alts.filter { passes(t, it, today, zone) }.mapNotNull { score(t, it, fuzzy) }.maxByOrNull { it.score } }

    private fun passes(t: TTask, q: Query, today: LocalDate, zone: ZoneId): Boolean {
        val n = t.n
        if (q.tags.any { tag -> n.tags.none { it.startsWith(tag) } }) return false
        if (q.lists.any { it !in n.list } || q.notLists.any { it in n.list }) return false
        if (q.priorities.isNotEmpty() && t.priority !in q.priorities) return false
        if (q.not.any { w -> w in n.title || w in n.body || n.tags.any { w in it } }) return false
        q.kind?.let { if (!fold(t.kind).startsWith(if (it == "task") "text" else it)) return false }
        if (q.due.isNotEmpty()) {
            val due = t.due?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            if (q.due.none { d ->
                when (d) {
                    "any" -> due != null
                    "none" -> due == null
                    "today" -> due == today
                    "tomorrow" -> due == today.plusDays(1)
                    "overdue" -> due != null && due < today
                    "week" -> due != null && due >= today && due < today.plusDays(7)
                    else -> true
                }
            }) return false
        }
        return true
    }

    private fun score(t: TTask, q: Query, fuzzy: Boolean): Hit? {
        var total = 0
        for (term in q.terms) {
            val s = if (fuzzy) fuzzy(t.n, term) else exact(t.n, term)
            if (s == 0) return null
            total += s
        }
        if (q.terms.size > 1 && !fuzzy && q.terms.joinToString(" ") in t.n.title) total += 8
        return Hit(t, total)
    }

    private fun exact(n: Norm, term: String): Int {
        var s = 0
        var i = n.title.indexOf(term)
        if (i >= 0) s += 10 + (if (wordStart(n.title, i)) 5 else 0) + (if (i == 0) 3 else 0)
        if (n.tags.any { it.startsWith(term) }) s += 6 else if (n.tags.any { term in it }) s += 4
        if (term in n.list) s += 3
        i = n.body.indexOf(term)
        if (i >= 0) s += 2 + if (wordStart(n.body, i)) 1 else 0
        return s
    }

    /** One typo (or a typo in the part typed so far) in a word of four letters or more. */
    private fun fuzzy(n: Norm, term: String): Int {
        if (term.length < 4) return exact(n, term)
        if (words(n.title).any { close(term, it) }) return 4
        if (n.tags.any { close(term, it) } || words(n.body).any { close(term, it) }) return 1
        return 0
    }

    private val separators = Regex("[\\s.,;:!?()\\[\\]{}<>\"'/\\\\|\\-_*#@&+=~`]+")
    private fun words(s: String) = s.split(separators).filter { it.isNotEmpty() }
    private fun wordStart(s: String, i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()
    private fun close(term: String, word: String) = lev1(term, word) || (word.length > term.length && lev1(term, word.substring(0, term.length)))

    /** Edit distance of at most 1, counting a swap of two neighbouring letters as one edit. */
    fun lev1(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        if (i == a.length && i == b.length) return true
        if (a.length == b.length)
            return a.substring(i + 1) == b.substring(i + 1) ||
                (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2))
        return if (a.length > b.length) a.substring(i + 1) == b.substring(i) else a.substring(i) == b.substring(i + 1)
    }

    private fun order(hits: List<Hit>, sort: Sort): List<Hit> = when (sort) {
        Sort.Best -> hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.task.priority }.thenByDescending { it.task.modified })
        Sort.Due -> hits.sortedWith(compareBy<Hit> { it.task.due == null }.thenBy { it.task.due ?: 0 }.thenByDescending { it.task.priority }.thenByDescending { it.score })
        Sort.Modified -> hits.sortedByDescending { it.task.modified }
        Sort.Created -> hits.sortedByDescending { it.task.created }
        Sort.Priority -> hits.sortedWith(compareByDescending<Hit> { it.task.priority }.thenByDescending { it.score }.thenByDescending { it.task.modified })
    }

    /** Where [terms] occur in [text], found the same accent- and case-blind way, as ranges of the original text (for highlighting). */
    fun marks(text: String, terms: List<String>): List<IntRange> {
        // Fold char by char, remembering which original char each folded char came from.
        val folded = StringBuilder(); val from = mutableListOf<Int>()
        text.forEachIndexed { i, c -> fold(c.toString()).forEach { f -> folded.append(f); from += i } }
        val f = folded.toString()
        val out = mutableListOf<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var at = f.indexOf(term)
            while (at >= 0) {
                out += from[at]..from[at + term.length - 1]
                at = f.indexOf(term, at + term.length)
            }
        }
        return out.sortedBy { it.first }
    }

    /** About [width] characters of [text] around the first match, for the line under a result's title. */
    fun snippet(text: String, terms: List<String>, width: Int = 90): String? {
        val flat = text.replace('\n', ' ')
        val at = marks(flat, terms).firstOrNull()?.first ?: return null
        val start = maxOf(0, at - width / 3)
        val end = minOf(flat.length, start + width)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }
}
