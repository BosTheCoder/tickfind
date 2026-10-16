using System.IO;
using System.Text.Json;

namespace Tickfind;

/// Everything you can change, saved as settings.json next to Tickfind.exe. Hand edits are read each time the box opens.
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

    public static string Path => System.IO.Path.Combine(AppContext.BaseDirectory, "settings.json");
    static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(Path)) ?? new(); }
        catch { return new(); }
    }

    public void Save() => File.WriteAllText(Path, JsonSerializer.Serialize(this, Json));

    public Tickfind.Sort SortOrder => Enum.TryParse<Tickfind.Sort>(Sort, true, out var s) ? s : Tickfind.Sort.Priority;
}
