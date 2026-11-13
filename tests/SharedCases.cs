using System.Text.Json.Nodes;
using Tickfind;
using Xunit;

/// search-cases.json: the checks every search implementation runs (this app, the Android app, the tasks page).
public class SharedCases
{
    static readonly DateTime Now = new(2026, 10, 16, 9, 0, 0);

    public static IEnumerable<object[]> Cases() =>
        JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "search-cases.json")))!["cases"]!.AsArray()
            .Select(c => new object[] { (string)c!["name"]! });

    [Theory, MemberData(nameof(Cases))]
    public void Search_does_what_search_cases_json_says(string name)
    {
        var c = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "search-cases.json")))!["cases"]!.AsArray()
            .First(x => (string)x!["name"]! == name)!;
        var i = 0;
        var tasks = c["tasks"]!.AsArray().Select(j =>
        {
            var age = new DateTimeOffset(Now.AddDays(-((int?)j!["ageDays"] ?? 0)));
            return new TTask($"t{++i}", "p", (string)j["title"]!, (string?)j["content"] ?? "", (string?)j["kind"] ?? "TEXT",
                (int?)j["priority"] ?? 0, Strings(j["tags"]), Strings(j["items"]),
                (int?)j["dueDays"] is int d ? new DateTimeOffset(Now.Date.AddDays(d)) : null, age, age, (string?)j["list"] ?? "Inbox");
        }).ToList();
        foreach (var check in c["checks"]!.AsArray())
        {
            var sort = Enum.Parse<Sort>((string?)check!["sort"] ?? "priority", true);
            var got = Search.Run(tasks, (string)check["q"]!, sort, Now, (string?)check["filter"] ?? "").Select(h => h.Task.Title);
            Assert.True(Strings(check["want"]).SequenceEqual(got), $"{name}: \"{check["q"]}\" gave [{string.Join(", ", got)}]");
        }
    }

    static string[] Strings(JsonNode? a) => a?.AsArray().Select(x => (string)x!).ToArray() ?? [];
}
