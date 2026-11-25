using System.IO;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Tickfind;

/// Everything you can change, saved as tickfind.json next to Tickfind.exe. Hand edits are read each time the box opens.
public class Settings
{
    public string? Hotkey { get; set; } = "Ctrl+Alt+F";   // null or "" = none
    public string Sort { get; set; } = "Priority";        // Priority | Best | Due | Modified | Created
    public int Results { get; set; } = 6;                 // matches shown in the compact box
    public int ExpandedResults { get; set; } = 25;        // and when expanded (Ctrl+E)
    public bool Expanded { get; set; }
    public string DefaultFilter { get; set; } = "";       // added to every search, e.g. "-~gcal" or "!high"
    public List<string> ExcludeLists { get; set; } = new();
    public bool ArchivedLists { get; set; }               // also index lists you've archived
    public string OpenIn { get; set; } = "App";           // App: TickTick for Windows (its search, on the title) | Web: the exact task in your browser
    public int RefreshMinutes { get; set; } = 5;          // re-sync when opened if the index is older than this
    public double Width { get; set; } = 640;
    public List<View> Views { get; set; } = View.Defaults();   // shown while the box is empty; the first one when it opens

    public class View
    {
        public string Name { get; set; } = "";
        public string Query { get; set; } = "";   // the search syntax; "a | b" is either

        public static List<View> Defaults() =>
        [
            new() { Name = "Today", Query = "due:overdue due:today -~gcal | #today" },
            new() { Name = "Inbox", Query = "~inbox" },
            new() { Name = "Recently edited", Query = "sort:modified -~gcal" },
            new() { Name = "Recently added", Query = "sort:created" },
            new() { Name = "Lists", Query = "is:list" },
        ];
    }

    public static readonly string Path = OwnFile("tickfind.json", "settings.json");

    /// A file next to the exe under a name no sibling app uses, so Tickfind can share a folder with Glance
    /// (which keeps settings.json and token.dat there too). Moves the file from its old name when Tickfind is
    /// the only exe in the folder; with another app there the old file may be that app's, so it's left alone.
    public static string OwnFile(string name, string oldName)
    {
        var dir = AppContext.BaseDirectory;
        var path = System.IO.Path.Combine(dir, name);
        var old = System.IO.Path.Combine(dir, oldName);
        try
        {
            if (!File.Exists(path) && File.Exists(old) && Directory.GetFiles(dir, "*.exe").Length == 1) File.Move(old, path);
        }
        catch { }
        return path;
    }
    // Relaxed escaping keeps "Ctrl+Alt+F" readable in the file rather than "Ctrl\u002BAlt\u002BF".
    static readonly JsonSerializerOptions Json = new() { WriteIndented = true, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(Path)) ?? new(); }
        catch { return new(); }
    }

    public void Save() => File.WriteAllText(Path, JsonSerializer.Serialize(this, Json));

    [JsonIgnore] public Tickfind.Sort SortOrder => Enum.TryParse<Tickfind.Sort>(Sort, true, out var s) ? s : Tickfind.Sort.Priority;
}
