using Tickfind;
using Xunit;

/// Most search behaviour is checked in SharedCases.cs (search-cases.json); these cover the rest.
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
    public void Is_list_shows_lists_by_task_count_and_words_narrow_by_name()
    {
        var tasks = new[] { T("a"), T("b", list: "🏠Property"), T("c", list: "🏠Property"), T("d", list: "Prop shopping") };

        Assert.True(Search.WantsLists("is:list prop"));
        Assert.False(Search.WantsLists("~inbox"));
        Assert.Equal(new[] { ("🏠Property", 2), ("Inbox", 1), ("Prop shopping", 1) }, Search.Lists(tasks, "is:list"));
        Assert.Equal(new[] { ("🏠Property", 2) }, Search.Lists(tasks, "is:list PROPERTY"));
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
    public void KeepEdits_counts_an_api_edit_and_keeps_our_own_stamp()
    {
        DateTimeOffset At(int h) => new(Now.AddHours(h));
        TTask V(string id, int modified, string etag) => new(id, "p", id, "", "TEXT", 0, [], [], null, At(modified), At(0), "Inbox", etag);
        var old = new[] { V("api", 0, "e1"), V("saved", 5, "e2"), V("app", 0, "e3"), V("same", 0, "e4"), V("new-cache", 0, "") };
        var fresh = new List<TTask> { V("api", 0, "e1b"), V("saved", 0, "e2"), V("app", 3, "e3b"), V("same", 0, "e4"), V("new-cache", 0, "x"), V("added", 1, "e5") };

        var got = Search.KeepEdits(fresh, old, At(9)).ToDictionary(t => t.Id, t => t.Modified);

        Assert.Equal(At(9), got["api"]);        // etag moved, modifiedTime didn't: changed through the API since last sync
        Assert.Equal(At(5), got["saved"]);      // stamped by our own save; the sync doesn't wind it back
        Assert.Equal(At(3), got["app"]);        // TickTick moved modifiedTime itself: trust it
        Assert.Equal(At(0), got["same"]);
        Assert.Equal(At(0), got["new-cache"]);  // cache from before etags were kept: no guess
        Assert.Equal(At(1), got["added"]);
    }

    [Fact]
    public void Snippet_centres_on_the_match()
    {
        var s = Search.Snippet(new string('x', 200) + " the Plumber came " + new string('y', 200), ["plumber"], 40);
        Assert.Contains("Plumber", s);
        Assert.StartsWith("…", s);
        Assert.EndsWith("…", s);
    }
}
