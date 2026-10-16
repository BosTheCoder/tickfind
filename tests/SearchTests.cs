using Tickfind;
using Xunit;

public class SearchTests
{
    static readonly DateTime Now = new(2026, 10, 16, 9, 0, 0);
    static int n;
    static TTask T(string title, int priority = 0, string content = "", string list = "Inbox", string[]? tags = null,
        string[]? items = null, DateTimeOffset? due = null, string kind = "TEXT", int ageDays = 0) =>
        new($"t{++n}", "p", title, content, kind, priority, tags ?? [], items ?? [], due,
            new DateTimeOffset(Now.AddDays(-ageDays)), new DateTimeOffset(Now.AddDays(-ageDays)), list);

    static string[] Titles(IEnumerable<TTask> tasks, string q, Sort sort = Sort.Priority, string filter = "") =>
        Search.Run(tasks, q, sort, Now, filter).Select(h => h.Task.Title).ToArray();

    [Fact]
    public void Priority_sort_puts_high_priority_first_and_best_match_puts_the_title_hit_first()
    {
        var tasks = new[] { T("Glance widget", priority: 3), T("Calendar ideas", priority: 5, content: "something like glance"), T("Unrelated", 5) };

        Assert.Equal(new[] { "Calendar ideas", "Glance widget" }, Titles(tasks, "glance"));
        Assert.Equal(new[] { "Glance widget", "Calendar ideas" }, Titles(tasks, "glance", Sort.Best));
        Assert.Equal(new[] { "Glance widget", "Calendar ideas" }, Titles(tasks, "glance sort:best"));   // typed sort wins
    }

    [Fact]
    public void Every_word_must_match_somewhere_ignoring_case_and_accents()
    {
        var tasks = new[]
        {
            T("Café plan", content: "book the room"),
            T("Café menu"),
            T("Room booking", items: ["call the café"]),
        };

        Assert.Equal(new[] { "Café plan", "Room booking" }, Titles(tasks, "CAFE room", Sort.Best));
    }

    [Fact]
    public void Filters_narrow_by_tag_list_priority_kind_due_and_excluded_words()
    {
        var tasks = new[]
        {
            T("Plumber quote", 5, list: "🏘 Property", tags: ["urgent"], due: new DateTimeOffset(Now.Date.AddDays(-1))),
            T("Plumber invoice", 1, list: "🏘 Property", due: new DateTimeOffset(Now.Date)),
            T("Plumber notes", 0, list: "Quick Notes", kind: "NOTE"),
        };

        Assert.Equal(new[] { "Plumber quote" }, Titles(tasks, "plumber #urg"));
        Assert.Equal(new[] { "Plumber quote", "Plumber invoice" }, Titles(tasks, "plumber ~property"));
        Assert.Equal(new[] { "Plumber notes" }, Titles(tasks, "plumber -~property"));
        Assert.Equal(new[] { "Plumber invoice" }, Titles(tasks, "!low"));
        Assert.Equal(new[] { "Plumber notes" }, Titles(tasks, "is:note"));
        Assert.Equal(new[] { "Plumber quote" }, Titles(tasks, "due:overdue"));
        Assert.Equal(new[] { "Plumber invoice" }, Titles(tasks, "due:today"));
        Assert.Equal(new[] { "Plumber quote", "Plumber notes" }, Titles(tasks, "plumber -invoice"));
    }

    [Fact]
    public void Due_values_combine_with_or()
    {
        var tasks = new[] { T("Late", due: new DateTimeOffset(Now.Date.AddDays(-1))), T("Now", due: new DateTimeOffset(Now.Date)), T("Later", due: new DateTimeOffset(Now.Date.AddDays(1))) };

        Assert.Equal(new[] { "Late", "Now" }, Titles(tasks, "due:overdue due:today", Sort.Due));
    }

    [Fact]
    public void Bar_means_or_and_the_filter_applies_to_each_side()
    {
        var tasks = new[]
        {
            T("Overdue chore", 3, due: new DateTimeOffset(Now.Date.AddDays(-1))),
            T("Daily standup", 5, list: "Daily Schedule - GCAL", due: new DateTimeOffset(Now.Date)),
            T("Tagged today", 1, tags: ["today"]),
            T("Calendar tagged", 5, list: "Events - GCAL", tags: ["today"]),
            T("Someday", 0),
        };

        Assert.Equal(new[] { "Overdue chore", "Tagged today" }, Titles(tasks, "due:overdue due:today -~gcal | #today", filter: "-~events"));
        Assert.Equal(new[] { "Someday" }, Titles(tasks, "someday |"));   // half-typed: the empty side adds nothing
    }

    [Fact]
    public void A_typo_still_finds_the_task_but_only_when_nothing_matches_exactly()
    {
        var tasks = new[] { T("Glance widget", 3), T("Glace dessert") };

        Assert.Equal(new[] { "Glance widget", "Glace dessert" }, Titles(tasks, "glnace"));
        Assert.Equal(new[] { "Glance widget" }, Titles(tasks, "glance"));   // an exact hit, so no near-misses
    }

    [Fact]
    public void Empty_query_lists_everything_in_the_default_sort()
    {
        var tasks = new[] { T("Old", 0, ageDays: 9), T("New", 0), T("Urgent", 5, ageDays: 30) };

        Assert.Equal(new[] { "Urgent", "New", "Old" }, Titles(tasks, ""));
        Assert.Equal(new[] { "New", "Old", "Urgent" }, Titles(tasks, "", Sort.Modified));
    }

    [Theory]
    [InlineData("glance", "glance", true)]
    [InlineData("glnace", "glance", true)]   // swap
    [InlineData("glace", "glance", true)]    // missing letter
    [InlineData("glancce", "glance", true)]  // extra letter
    [InlineData("glence", "glance", true)]   // wrong letter
    [InlineData("gnalce", "glance", false)]
    [InlineData("gl", "glance", false)]
    public void Lev1(string a, string b, bool close) => Assert.Equal(close, Search.Lev1(a, b));

    [Fact]
    public void Snippet_centres_on_the_match()
    {
        var s = Search.Snippet(new string('x', 200) + " the Plumber came " + new string('y', 200), ["plumber"], 40);
        Assert.Contains("Plumber", s);
        Assert.StartsWith("…", s);
        Assert.EndsWith("…", s);
    }
}
