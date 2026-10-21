using System.Globalization;
using System.Text;
using System.Text.Json.Serialization;

namespace Tickfind;

/// One open task or note, as cached from TickTick. Content holds the note text (or a checklist's description).
public record TTask(string Id, string ProjectId, string Title, string Content, string Kind, int Priority,
    string[] Tags, string[] Items, DateTimeOffset? Due, DateTimeOffset Modified, DateTimeOffset Created, string List)
{
    [JsonIgnore] internal Norm N => n ??= new Norm(this);
    Norm? n;
}

/// Lower-cased, accent-free copies of the searchable fields, built once per task.
sealed class Norm(TTask t)
{
    public readonly string Title = Search.Fold(t.Title), Body = Search.Fold(t.Content + "\n" + string.Join("\n", t.Items)), List = Search.Fold(t.List);
    public readonly string[] Tags = t.Tags.Select(Search.Fold).ToArray();
}

public enum Sort { Priority, Best, Due, Modified, Created }

public record Hit(TTask Task, int Score);

/// What the user typed, split into words to match and filters.
public class Query
{
    public readonly List<string> Terms = new(), Not = new(), Tags = new(), Lists = new(), NotLists = new();
    public readonly HashSet<int> Priorities = new();
    public readonly HashSet<string> Due = new();   // any of these
    public string? Kind;
    public Sort? Sort;
    public bool Empty => Terms.Count == 0;
}

/// Local search over the cached tasks: every word must match somewhere (title, tags, list, notes, checklist),
/// title hits score highest, and only when nothing matches exactly does it allow one typo per word.
public static class Search
{
    public static string Fold(string s)
    {
        var d = s.ToLowerInvariant().Normalize(NormalizationForm.FormD);
        var b = new StringBuilder(d.Length);
        foreach (var c in d) if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) b.Append(c);
        return b.ToString();
    }

    static readonly Dictionary<string, int> PriorityWords = new()
    {
        ["high"] = 5, ["h"] = 5, ["5"] = 5, ["medium"] = 3, ["med"] = 3, ["m"] = 3, ["3"] = 3,
        ["low"] = 1, ["l"] = 1, ["1"] = 1, ["none"] = 0, ["n"] = 0, ["0"] = 0,
    };

    /// Everything typed, every side of a "|" together (for highlighting the words).
    public static Query Parse(string text)
    {
        var q = new Query();
        foreach (var raw in Tokens(text)) if (raw != "|") Add(q, raw);
        return q;
    }

    /// One query per side of each "|", each with [filter]'s tokens first. Empty sides (a "|" typed but nothing after it) are dropped.
    static List<Query> Alternatives(string text, string filter)
    {
        var sides = new List<List<string>> { new() };
        foreach (var raw in Tokens(text)) if (raw == "|") sides.Add(new()); else sides[^1].Add(raw);
        var f = Tokens(filter).ToList();
        return sides.Where(s => s.Count > 0).DefaultIfEmpty(new()).Select(side =>
        {
            var q = new Query();
            foreach (var raw in f.Concat(side)) Add(q, raw);
            return q;
        }).ToList();
    }

    static void Add(Query q, string raw)
    {
        var w = Fold(raw);
        if (w.Length > 1 && w[0] == '#') q.Tags.Add(w[1..]);
        else if (w.Length > 1 && w[0] == '~') q.Lists.Add(w[1..]);
        else if (w.StartsWith("list:") && w.Length > 5) q.Lists.Add(w[5..]);
        else if (w.Length > 2 && w.StartsWith("-~")) q.NotLists.Add(w[2..]);
        else if (w.Length > 1 && w[0] == '!' && PriorityWords.TryGetValue(w[1..], out var p)) q.Priorities.Add(p);
        else if (w.StartsWith("is:") && w.Length > 3) q.Kind = w[3..];
        else if (w.StartsWith("due:") && w.Length > 4) q.Due.Add(w[4..]);
        else if (w == "has:due") q.Due.Add("any");
        else if (w.StartsWith("sort:") && Enum.TryParse<Sort>(w[5..] == "best" || w[5..] == "relevance" ? "Best" : w[5..], true, out var s)) q.Sort = s;
        else if (w.Length > 1 && w[0] == '-') q.Not.Add(w[1..]);
        else if (w.Length > 0) q.Terms.Add(w);
    }

    /// Splits on spaces, keeping "quoted phrases" together.
    static IEnumerable<string> Tokens(string text)
    {
        var b = new StringBuilder();
        var quoted = false;
        foreach (var c in text)
        {
            if (c == '"') { quoted = !quoted; continue; }
            if (char.IsWhiteSpace(c) && !quoted) { if (b.Length > 0) yield return b.ToString(); b.Clear(); }
            else b.Append(c);
        }
        if (b.Length > 0) yield return b.ToString();
    }

    /// [text] may hold "a | b" (either side); [filter] (the default filter) applies to every side.
    public static List<Hit> Run(IEnumerable<TTask> tasks, string text, Sort defaultSort, DateTime now, string filter = "")
    {
        var alts = Alternatives(text, filter);
        var list = tasks as IList<TTask> ?? tasks.ToList();
        var hits = Match(list, alts, now, fuzzy: false);
        if (hits.Count == 0 && alts.Any(q => !q.Empty)) hits = Match(list, alts, now, fuzzy: true);
        return Order(hits, alts.Select(q => q.Sort).FirstOrDefault(s => s != null) ?? defaultSort).ToList();
    }

    /// "is:list" asks for lists rather than tasks.
    public static bool WantsLists(string text) => Parse(text).Kind?.StartsWith("list") == true;

    /// The lists holding [tasks], with how many each, most first. Every plain word in [text] has to be in the list's name.
    public static List<(string Name, int Count)> Lists(IEnumerable<TTask> tasks, string text)
    {
        var words = Parse(text).Terms;
        return tasks.GroupBy(t => t.List).Where(g => words.All(g.First().N.List.Contains))
            .Select(g => (g.Key, g.Count())).OrderByDescending(l => l.Item2).ThenBy(l => l.Key).ToList();
    }

    /// Each task that some side lets through, scored by its best side.
    static List<Hit> Match(IList<TTask> tasks, List<Query> alts, DateTime now, bool fuzzy) =>
        tasks.Select(t => alts.Where(q => Passes(t, q, now)).Select(q => Score(t, q, fuzzy)).OfType<Hit>().MaxBy(h => h.Score))
            .OfType<Hit>().ToList();

    static bool Passes(TTask t, Query q, DateTime now)
    {
        var n = t.N;
        if (q.Tags.Any(tag => !n.Tags.Any(x => x.StartsWith(tag)))) return false;
        if (q.Lists.Any(l => !n.List.Contains(l)) || q.NotLists.Any(l => n.List.Contains(l))) return false;
        if (q.Priorities.Count > 0 && !q.Priorities.Contains(t.Priority)) return false;
        if (q.Not.Any(w => n.Title.Contains(w) || n.Body.Contains(w) || n.Tags.Any(x => x.Contains(w)))) return false;
        if (q.Kind != null && !Fold(t.Kind).StartsWith(q.Kind == "task" ? "text" : q.Kind)) return false;
        if (q.Due.Count > 0)
        {
            var today = now.Date;
            var due = t.Due?.ToLocalTime().Date;
            if (!q.Due.Any(d => d switch
            {
                "any" => due != null,
                "none" => due == null,
                "today" => due == today,
                "tomorrow" => due == today.AddDays(1),
                "overdue" => due < today,
                "week" => due >= today && due < today.AddDays(7),
                _ => true,
            })) return false;
        }
        return true;
    }

    static Hit? Score(TTask t, Query q, bool fuzzy)
    {
        var n = t.N;
        var total = 0;
        foreach (var term in q.Terms)
        {
            var s = fuzzy ? Fuzzy(n, term) : Exact(n, term);
            if (s == 0) return null;
            total += s;
        }
        if (q.Terms.Count > 1 && !fuzzy && n.Title.Contains(string.Join(' ', q.Terms))) total += 8;
        return new Hit(t, total);
    }

    static int Exact(Norm n, string term)
    {
        var s = 0;
        var i = n.Title.IndexOf(term, StringComparison.Ordinal);
        if (i >= 0) s += 10 + (WordStart(n.Title, i) ? 5 : 0) + (i == 0 ? 3 : 0);
        if (n.Tags.Any(x => x.StartsWith(term))) s += 6;
        else if (n.Tags.Any(x => x.Contains(term))) s += 4;
        if (n.List.Contains(term)) s += 3;
        i = n.Body.IndexOf(term, StringComparison.Ordinal);
        if (i >= 0) s += 2 + (WordStart(n.Body, i) ? 1 : 0);
        return s;
    }

    /// One typo (or a typo in the part typed so far) in a word of four letters or more.
    static int Fuzzy(Norm n, string term)
    {
        if (term.Length < 4) return Exact(n, term);
        if (Words(n.Title).Any(w => Close(term, w))) return 4;
        if (n.Tags.Any(x => Close(term, x)) || Words(n.Body).Any(w => Close(term, w))) return 1;
        return 0;
    }

    static IEnumerable<string> Words(string s) => s.Split(Separators, StringSplitOptions.RemoveEmptyEntries);
    static readonly char[] Separators = " \n\t.,;:!?()[]{}<>\"'/\\|-_*#@&+=~`".ToCharArray();

    static bool WordStart(string s, int i) => i == 0 || !char.IsLetterOrDigit(s[i - 1]);

    static bool Close(string term, string word) =>
        Lev1(term, word) || (word.Length > term.Length && Lev1(term, word[..term.Length]));

    /// Edit distance of at most 1, counting a swap of two neighbouring letters as one edit.
    public static bool Lev1(string a, string b)
    {
        if (Math.Abs(a.Length - b.Length) > 1) return false;
        int i = 0;
        while (i < a.Length && i < b.Length && a[i] == b[i]) i++;
        if (i == a.Length && i == b.Length) return true;
        if (a.Length == b.Length)
            return a[(i + 1)..] == b[(i + 1)..]
                || (i + 1 < a.Length && a[i] == b[i + 1] && a[i + 1] == b[i] && a[(i + 2)..] == b[(i + 2)..]);
        return a.Length > b.Length ? a[(i + 1)..] == b[i..] : a[i..] == b[(i + 1)..];
    }

    static IEnumerable<Hit> Order(List<Hit> hits, Sort sort) => sort switch
    {
        Sort.Best => hits.OrderByDescending(h => h.Score).ThenByDescending(h => h.Task.Priority).ThenByDescending(h => h.Task.Modified),
        Sort.Due => hits.OrderBy(h => h.Task.Due == null).ThenBy(h => h.Task.Due).ThenByDescending(h => h.Task.Priority).ThenByDescending(h => h.Score),
        Sort.Modified => hits.OrderByDescending(h => h.Task.Modified),
        Sort.Created => hits.OrderByDescending(h => h.Task.Created),
        _ => hits.OrderByDescending(h => h.Task.Priority).ThenByDescending(h => h.Score).ThenByDescending(h => h.Task.Modified),
    };

    /// About [width] characters of [text] around the first word that matches, for the line under a result's title.
    public static string? Snippet(string text, IReadOnlyList<string> terms, int width = 90)
    {
        if (text.Length == 0) return null;
        var flat = text.Replace('\n', ' ');
        var at = terms.Select(t => CultureInfo.InvariantCulture.CompareInfo.IndexOf(flat, t, CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace))
            .Where(i => i >= 0).DefaultIfEmpty(-1).Min();
        if (at < 0) return null;
        var start = Math.Max(0, at - width / 3);
        var s = flat.Substring(start, Math.Min(width, flat.Length - start)).Trim();
        return (start > 0 ? "…" : "") + s + (start + width < flat.Length ? "…" : "");
    }
}
