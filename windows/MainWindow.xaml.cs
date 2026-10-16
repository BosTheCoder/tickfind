using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace Tickfind;

/// The search box. Hidden until the shortcut; click away or Esc and it hides again (it never closes).
public partial class MainWindow : Window
{
    readonly bool demo;
    Settings s = Settings.Load();
    DateTime settingsTime;
    readonly TickTick tt = new();
    readonly IntPtr hwnd;

    List<TTask> tasks = new();
    Dictionary<string, string> lists = new();
    DateTime synced = DateTime.MinValue;
    bool syncing, tips, busy;   // busy: a save or completion is on its way to TickTick
    string? note;               // the status line's message, when there is one (errors, "Saved")
    Updater.Release? update;

    record Row(Border View, Action Open, Action? Alt);
    readonly List<Row> rows = new();
    int sel;
    List<(string Id, string Title, string ProjectId)>? server;   // TickTick's own search results, while shown

    // The task open in the editor, as TickTick had it when it loaded (for working out what changed)
    TTask? editing;
    JsonObject? loaded;
    int editPriority;
    List<JsonObject> editItems = new();
    Action? bannerA, bannerB;

    static string CachePath => Path.Combine(AppContext.BaseDirectory, "tasks.json");
    record Cache(DateTime Synced, Dictionary<string, string> Lists, List<TTask> Tasks);

    static readonly Brush SelBrush = new SolidColorBrush(Color.FromArgb(0x2C, 0xFF, 0xFF, 0xFF));
    static readonly Brush Accent = new SolidColorBrush(Color.FromRgb(0x8F, 0xB0, 0xFF));
    static readonly Brush Late = new SolidColorBrush(Color.FromRgb(0xFF, 0x7A, 0x70));

    public MainWindow(bool demo, bool pin = false, string? query = null)
    {
        this.demo = demo;
        InitializeComponent();
        if (!File.Exists(Settings.Path) && !demo) { StartsWithWindows = true; s.Save(); }   // first run: a shortcut app has to be running
        settingsTime = File.Exists(Settings.Path) ? File.GetLastWriteTimeUtc(Settings.Path) : default;
        if (demo) { tasks = Demo.Tasks(); synced = DateTime.Now; }
        else LoadCache();

        hwnd = new WindowInteropHelper(this).EnsureHandle();   // the hotkey needs a window before it's ever shown
        HwndSource.FromHwnd(hwnd).AddHook(WndProc);
        Native.Acrylic(hwnd);
        RegisterHotkey();

        Box.TextChanged += (_, _) => { server = null; tips = false; sel = 0; Hint.Visibility = Box.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed; Render(); };
        if (query != null) Box.Text = query;
        PreviewKeyDown += OnKey;
        Deactivated += (_, _) => { if (!pin) Hide(); };
        GearButton.MouseLeftButtonUp += (_, _) => ShowMenu();
        ExpandButton.MouseLeftButtonUp += (_, _) => ToggleExpanded();
        BackButton.MouseLeftButtonUp += (_, _) => Back();
        Status.MouseLeftButtonUp += (_, _) => { if (update != null && note == null) _ = Install(); };
        OpenButton.Click += (_, _) => { if (editing != null) OpenInTickTick(editing.ProjectId, editing.Id, EditTitle.Text); };   // only hides: edits stay
        CopyButton.Click += (_, _) => CopyLink();
        SaveButton.Click += async (_, _) => await Save(force: false, complete: false);
        CompleteButton.Click += async (_, _) => await Save(force: false, complete: true);
        BannerA.Click += (_, _) => { Banner.Visibility = Visibility.Collapsed; bannerA?.Invoke(); };
        BannerB.Click += (_, _) => { Banner.Visibility = Visibility.Collapsed; bannerB?.Invoke(); };
        AddItem.TextChanged += (_, _) => AddItemHint.Visibility = AddItem.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        AddItem.KeyDown += (_, e) =>
        {
            if (e.Key != Key.Enter || AddItem.Text.Trim() is not { Length: > 0 } title) return;
            editItems.Add(new JsonObject { ["title"] = title, ["status"] = 0 });
            AddItem.Clear();
            RenderItems();
            e.Handled = true;
        };

        var timer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(30) };
        timer.Tick += async (_, _) => { if (!IsVisible) await Sync(); _ = CheckUpdate(); };
        timer.Start();
        _ = CheckUpdate();
    }

    IntPtr WndProc(IntPtr h, int msg, IntPtr wp, IntPtr lp, ref bool handled)
    {
        if (msg == Native.WM_HOTKEY)
        {
            if (IsVisible && IsActive) Hide(); else Summon();
            handled = true;
        }
        return IntPtr.Zero;
    }

    void RegisterHotkey()
    {
        if (!Native.Hotkey(hwnd, s.Hotkey)) note = $"{s.Hotkey} is taken by another app. Pick another shortcut under ⚙.";
    }

    /// Opens the box on the monitor the mouse is on, near the top, with the last search selected so typing replaces it.
    public void Summon()
    {
        if (File.Exists(Settings.Path) && File.GetLastWriteTimeUtc(Settings.Path) != settingsTime)
        {
            settingsTime = File.GetLastWriteTimeUtc(Settings.Path);
            s = Settings.Load();
            RegisterHotkey();
        }
        if (EditPage.Visibility == Visibility.Visible && Changes().Count == 0) ShowSearch();
        Width = s.Width;
        var wa = Native.WorkAreaAtCursor(this);
        Left = wa.Left + (wa.Width - Width) / 2;
        Top = wa.Top + wa.Height * 0.15;
        Scroll.MaxHeight = wa.Height * 0.6;
        EditScroll.MaxHeight = wa.Height * 0.45;
        Show();
        Activate();
        if (SearchPage.Visibility == Visibility.Visible) { Box.Focus(); Box.SelectAll(); }
        Render();
        if (!demo && tt.SignedIn && DateTime.Now - synced > TimeSpan.FromMinutes(s.RefreshMinutes)) _ = Sync();
    }

    /// Renders the box over a dark backdrop (standing in for the acrylic glass) at 2x, for docs and checks.
    public void Snapshot(string path, bool edit)
    {
        if (edit && rows.Count > 0) rows[0].Open();
        Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, () =>
        {
            UpdateLayout();
            double w = Root.ActualWidth, h = Root.ActualHeight, scale = 2;
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.DrawRoundedRectangle(new LinearGradientBrush(Color.FromRgb(0x30, 0x34, 0x4C), Color.FromRgb(0x1A, 0x1C, 0x28), 90), null, new Rect(0, 0, w, h), 10, 10);
                dc.DrawRectangle(new VisualBrush(Root), null, new Rect(0, 0, w, h));
            }
            var bmp = new System.Windows.Media.Imaging.RenderTargetBitmap((int)(w * scale), (int)(h * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            bmp.Render(dv);
            var png = new System.Windows.Media.Imaging.PngBitmapEncoder();
            png.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bmp));
            using (var f = File.Create(path)) png.Save(f);
            Application.Current.Shutdown();
        });
    }

    // ---------- Data ----------

    void LoadCache()
    {
        try
        {
            var c = JsonSerializer.Deserialize<Cache>(File.ReadAllText(CachePath))!;
            (tasks, lists, synced) = (c.Tasks, c.Lists, c.Synced);
        }
        catch { }
    }

    void SaveCache()
    {
        if (!demo) File.WriteAllText(CachePath, JsonSerializer.Serialize(new Cache(synced, lists, tasks)));
    }

    async Task Sync()
    {
        if (demo || syncing || !tt.SignedIn) return;
        syncing = true;
        RenderStatus();
        try
        {
            var (fresh, names, failed) = await tt.Sync(s.ArchivedLists);
            // A list that couldn't be read keeps its old tasks, and the index stays due for a sync, so nothing vanishes from search.
            fresh.AddRange(tasks.Where(t => failed.Contains(t.List)));
            (tasks, lists, synced) = (fresh, names, failed.Count == 0 ? DateTime.Now : synced);
            note = failed.Count > 0 ? $"Couldn't read {string.Join(", ", failed)}" : null;
            SaveCache();
        }
        catch (NeedsSignIn) { note = null; }
        catch (Exception e) { note = "Sync failed: " + e.Message; }
        finally { syncing = false; }
        if (SearchPage.Visibility == Visibility.Visible) Render(); else RenderStatus();
    }

    async Task SignIn()
    {
        note = "Finish signing in in your browser…";
        RenderStatus();
        try
        {
            await tt.SignIn();
            note = null;
            Summon();
            await Sync();
        }
        catch (Exception e) { note = "Sign-in failed: " + e.Message; }
        Render();
    }

    async Task CheckUpdate(bool quiet = true)
    {
        try
        {
            update = await Updater.Newer();
            if (!quiet && update == null) note = $"You're on the latest version (v{Updater.Current})";
        }
        catch (Exception e) { if (!quiet) note = "Couldn't check for updates: " + e.Message; }
        RenderStatus();
    }

    async Task Install()
    {
        try
        {
            note = $"Installing {update!.Tag}…";
            RenderStatus();
            await Updater.Install(update);
        }
        catch (Exception e) { note = "Update didn't install: " + e.Message; RenderStatus(); }
    }

    // ---------- Search page ----------

    void Render()
    {
        Results.Children.Clear();
        rows.Clear();
        ExpandButton.Text = s.Expanded ? "" : "";
        var max = s.Expanded ? s.ExpandedResults : s.Results;
        if (!demo && !tt.SignedIn)
            AddRow(Simple("", "Sign in to TickTick", "Opens your browser. Tickfind asks to read and edit your tasks, nothing else."), () => _ = SignIn());
        else if (tips)
            foreach (var (syntax, what) in Tips) AddRow(Simple("", syntax, what), () => { });
        else if (server != null)
        {
            AddRow(Simple("", $"TickTick's results for “{Box.Text.Trim()}”", "Completed tasks included. Esc goes back."), () => { server = null; Render(); });
            foreach (var r in server.Take(Math.Max(max, 10)))
                AddRow(Simple("", r.Title, lists.GetValueOrDefault(r.ProjectId, "")), () => OpenEditor(r.Id, lists.GetValueOrDefault(r.ProjectId, "")),
                    () => OpenInTickTick(r.ProjectId, r.Id, r.Title));
            if (server.Count == 0) AddRow(Simple("", "TickTick found nothing either", ""), () => { });
        }
        else
        {
            var q = Box.Text.Trim();
            var pool = s.ExcludeLists.Count == 0 ? tasks : tasks.Where(t => !s.ExcludeLists.Contains(t.List)).ToList();
            var hits = Search.Run(pool, (s.DefaultFilter + " " + q).Trim(), s.SortOrder, DateTime.Now);
            var terms = Search.Parse(q).Terms;
            foreach (var h in hits.Take(max))
                AddRow(TaskRow(h.Task, terms), () => OpenEditor(h.Task), () => OpenInTickTick(h.Task.ProjectId, h.Task.Id, h.Task.Title));
            if (hits.Count > max)
                AddRow(Simple("", $"{hits.Count - max} more", s.Expanded ? "Add a word or a filter to narrow it down" : "Ctrl+E shows more"), () => { if (!s.Expanded) ToggleExpanded(); });
            if (terms.Count > 0)
                AddRow(Simple("", $"Search all of TickTick for “{q}”", "TickTick's own search, completed tasks included"), () => _ = RunServerSearch(q));
            if (tasks.Count == 0)
                AddRow(Simple("", syncing ? "Reading your tasks from TickTick…" : "Nothing indexed yet", "Ctrl+R syncs now"), () => _ = Sync());
        }
        sel = Math.Clamp(sel, 0, Math.Max(0, rows.Count - 1));
        Highlight();
        RenderStatus();
    }

    void RenderStatus()
    {
        Status.Text = syncing ? "Syncing with TickTick…"
            : note ?? (update != null ? $"Update to {update.Tag} available: click to install"
            : demo ? "Demo: made-up tasks, nothing is saved"
            : synced == DateTime.MinValue ? "" : $"{tasks.Count:N0} tasks · synced {Ago(synced)}");
        Status.Cursor = update != null && note == null ? Cursors.Hand : Cursors.Arrow;
    }

    static readonly (string, string)[] Tips =
    [
        ("plumber quote", "Every word has to match: title, notes, checklist, tags or list"),
        ("\"kitchen tap\"", "These words together"),
        ("-invoice", "Leave out tasks with this word"),
        ("#home", "Tagged home (or a tag starting with it)"),
        ("~property   -~gcal", "In a list whose name contains this, or not"),
        ("!high  !med  !low  !none", "Priority"),
        ("due:today  due:tomorrow  due:week  due:overdue  has:due  due:none", "Due date"),
        ("is:note  is:checklist  is:task", "Kind"),
        ("sort:priority  sort:best  sort:due  sort:modified  sort:created", "This search only; set the default under ⚙"),
    ];

    async Task RunServerSearch(string q)
    {
        if (demo) { note = "The demo can't ask TickTick"; RenderStatus(); return; }
        note = "Asking TickTick…";
        RenderStatus();
        try
        {
            var found = await tt.ServerSearch(q);
            note = null;
            if (Box.Text.Trim() == q) { server = found; sel = 1; }
        }
        catch (NeedsSignIn) { note = null; }
        catch (Exception e) { note = "TickTick's search failed: " + e.Message; }
        Render();
    }

    void AddRow(FrameworkElement content, Action open, Action? alt = null)
    {
        var b = new Border { Child = content, Padding = new Thickness(8, 7, 8, 7), CornerRadius = new CornerRadius(6), Background = Brushes.Transparent, Cursor = Cursors.Hand };
        var i = rows.Count;
        b.MouseMove += (_, _) => { if (sel != i) { sel = i; Highlight(); } };
        b.MouseLeftButtonUp += (_, _) => open();
        Results.Children.Add(b);
        rows.Add(new Row(b, open, alt));
    }

    void Highlight()
    {
        for (var i = 0; i < rows.Count; i++) rows[i].View.Background = i == sel ? SelBrush : Brushes.Transparent;
        if (rows.Count > 0) rows[sel].View.BringIntoView();
    }

    FrameworkElement TaskRow(TTask t, List<string> terms)
    {
        var stack = new StackPanel();
        var title = new TextBlock { FontSize = 14, TextTrimming = TextTrimming.CharacterEllipsis };
        Marked(title.Inlines, t.Title.Length > 0 ? t.Title : "(no title)", terms);
        stack.Children.Add(title);
        // Where it matched, when that wasn't the title
        if (terms.Count > 0 && !terms.All(x => Search.Fold(t.Title).Contains(x))
            && Search.Snippet(t.Content + " " + string.Join(" · ", t.Items), terms) is { } snip)
        {
            var line = new TextBlock { FontSize = 12, Opacity = 0.75, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 2, 0, 0) };
            Marked(line.Inlines, snip, terms);
            stack.Children.Add(line);
        }
        var meta = new TextBlock { FontSize = 11.5, Opacity = 0.6, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 2, 0, 0) };
        meta.Inlines.Add(new Run(t.List));
        if (DueText(t.Due) is { } due)
        {
            var run = new Run("  ·  " + due.Text);
            if (due.Late) run.Foreground = Late;
            meta.Inlines.Add(run);
        }
        foreach (var tag in t.Tags) meta.Inlines.Add(new Run("  #" + tag));
        if (t.Kind == "NOTE") meta.Inlines.Add(new Run("  ·  note"));
        stack.Children.Add(meta);
        var bar = new Border { Width = 3, CornerRadius = new CornerRadius(2), Background = PriorityBrush(t.Priority), Margin = new Thickness(0, 2, 10, 2) };
        var dock = new DockPanel();
        DockPanel.SetDock(bar, Dock.Left);
        dock.Children.Add(bar);
        dock.Children.Add(stack);
        return dock;
    }

    static FrameworkElement Simple(string icon, string title, string sub)
    {
        var dock = new DockPanel();
        var i = new TextBlock { Text = icon, FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"), FontSize = 14, Opacity = 0.7, Width = 24, Margin = new Thickness(0, 2, 0, 0) };
        DockPanel.SetDock(i, Dock.Left);
        dock.Children.Add(i);
        var stack = new StackPanel();
        stack.Children.Add(new TextBlock { Text = title, FontSize = 13.5, TextTrimming = TextTrimming.CharacterEllipsis });
        if (sub.Length > 0) stack.Children.Add(new TextBlock { Text = sub, FontSize = 11.5, Opacity = 0.55, TextTrimming = TextTrimming.CharacterEllipsis });
        dock.Children.Add(stack);
        return dock;
    }

    /// The matched words in bold and tinted, found the same accent- and case-blind way the search does.
    static void Marked(InlineCollection inlines, string text, List<string> terms)
    {
        var marks = new bool[text.Length];
        var ci = CultureInfo.InvariantCulture.CompareInfo;
        foreach (var term in terms)
            for (var from = 0; from < text.Length;)
            {
                var i = ci.IndexOf(text.AsSpan(from), term, CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace, out var len);
                if (i < 0 || len == 0) break;
                Array.Fill(marks, true, from + i, len);
                from += i + len;
            }
        for (int start = 0, k = 1; k <= text.Length; k++)
            if (k == text.Length || marks[k] != marks[start])
            {
                var r = new Run(text[start..k]);
                if (marks[start]) { r.FontWeight = FontWeights.Bold; r.Foreground = Accent; }
                inlines.Add(r);
                start = k;
            }
    }

    static Brush PriorityBrush(int p) => new SolidColorBrush(p switch
    {
        5 => Color.FromRgb(0xD5, 0x2B, 0x24),
        3 => Color.FromRgb(0xFA, 0xA8, 0x0C),
        1 => Color.FromRgb(0x47, 0x72, 0xFA),
        _ => Color.FromArgb(0x40, 0xFF, 0xFF, 0xFF),
    });

    static (string Text, bool Late)? DueText(DateTimeOffset? due)
    {
        if (due is not { } d) return null;
        var day = d.ToLocalTime().Date;
        var today = DateTime.Today;
        var days = (day - today).TotalDays;
        var text = days switch
        {
            0 => "Today",
            1 => "Tomorrow",
            -1 => "Yesterday",
            > 0 and < 7 => day.ToString("dddd"),
            _ => day.ToString(day.Year == today.Year ? "d MMM" : "d MMM yyyy"),
        };
        return (text, days < 0);
    }

    static string Ago(DateTime t)
    {
        var m = (DateTime.Now - t).TotalMinutes;
        return m < 1 ? "just now" : m < 60 ? $"{(int)m} min ago" : m < 60 * 24 ? $"{(int)(m / 60)} h ago" : t.ToString("d MMM HH:mm");
    }

    void OnKey(object? sender, KeyEventArgs e)
    {
        var ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
        if (EditPage.Visibility == Visibility.Visible)
        {
            if (e.Key == Key.Escape) Back();
            else if (ctrl && e.Key == Key.S) _ = Save(force: false, complete: false);
            else if (ctrl && e.Key == Key.Enter && editing != null) OpenInTickTick(editing.ProjectId, editing.Id, EditTitle.Text);
            else if (ctrl && e.Key == Key.L) CopyLink();
            else return;
            e.Handled = true;
            return;
        }
        switch (e.Key)
        {
            case Key.Escape when server != null || tips: server = null; tips = false; Render(); break;
            case Key.Escape: Hide(); break;
            case Key.Down when rows.Count > 0: sel = (sel + 1) % rows.Count; Highlight(); break;
            case Key.Up when rows.Count > 0: sel = (sel - 1 + rows.Count) % rows.Count; Highlight(); break;
            case Key.Enter when rows.Count > 0: (ctrl ? rows[sel].Alt ?? rows[sel].Open : rows[sel].Open)(); break;
            case Key.E when ctrl: ToggleExpanded(); break;
            case Key.R when ctrl: _ = Sync(); break;
            default: return;
        }
        e.Handled = true;
    }

    void ToggleExpanded()
    {
        s.Expanded = !s.Expanded;
        Persist();
        Render();
    }

    void Persist()
    {
        s.Save();
        settingsTime = File.GetLastWriteTimeUtc(Settings.Path);
    }

    /// TickTick for Windows has no open-this-task link, so "App" opens its search on the title; "Web" opens the exact task.
    void OpenInTickTick(string projectId, string id, string title)
    {
        var app = s.OpenIn != "Web" && Registry.ClassesRoot.OpenSubKey("ticktick") != null;
        var keyword = new string(title.Where(c => c is not ('&' or '=' or '?' or '#' or '%' or '+')).ToArray()).Trim();
        var url = app && keyword.Length > 0 ? "ticktick://v1/search?keyword=" + Uri.EscapeDataString(keyword[..Math.Min(80, keyword.Length)]) : TickTick.WebUrl(projectId, id);
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            Hide();
        }
        catch (Exception e) { note = "Couldn't open TickTick: " + e.Message; RenderStatus(); }
    }

    /// The exact task's web link, to paste into a note or chat.
    async void CopyLink()
    {
        if (editing == null) return;
        try { Clipboard.SetText(TickTick.WebUrl(editing.ProjectId, editing.Id)); }
        catch (Exception e) { EditStatus.Text = "Couldn't copy the link: " + e.Message; return; }
        CopyButton.Content = "Copied";
        await Task.Delay(1500);
        CopyButton.Content = "Copy link";
    }

    // ---------- Quick edit ----------

    void OpenEditor(TTask t, JsonObject? fresh = null)
    {
        editing = t;
        loaded = null;
        editItems = new();
        EditList.Text = t.List;
        EditTitle.Text = t.Title;
        EditContent.Text = t.Content;
        editPriority = t.Priority;
        ItemsPanel.Children.Clear();
        AddItemRow.Visibility = Visibility.Collapsed;
        Banner.Visibility = Visibility.Collapsed;
        CompleteButton.Visibility = Visibility.Visible;
        RenderPriority();
        Editable(false);
        SearchPage.Visibility = Visibility.Collapsed;
        EditPage.Visibility = Visibility.Visible;
        if (demo)
        {
            Fill(new JsonObject
            {
                ["id"] = t.Id, ["projectId"] = t.ProjectId, ["title"] = t.Title, ["priority"] = t.Priority, ["kind"] = t.Kind, ["status"] = 0,
                [t.Kind == "CHECKLIST" ? "desc" : "content"] = t.Content,
                ["items"] = new JsonArray(t.Items.Select((x, i) => (JsonNode)new JsonObject { ["id"] = $"i{i}", ["title"] = x, ["status"] = 0, ["sortOrder"] = i }).ToArray()),
            });
            EditStatus.Text = "Demo: Save and Complete don't reach TickTick";
            return;
        }
        if (fresh != null) { Fill(fresh); return; }
        EditStatus.Text = "Loading the latest from TickTick…";
        _ = Load(t.Id);
    }

    /// A task from TickTick's own search: not in the index (it may be completed), so load it first.
    async void OpenEditor(string id, string list)
    {
        if (editing != null || note == "Opening…") return;   // a second Enter while the first is loading
        note = "Opening…";
        RenderStatus();
        try
        {
            var j = await tt.Get(id);
            note = null;
            if (editing == null && server != null) OpenEditor(TickTick.Parse(j, list), j);
            else RenderStatus();
        }
        catch (Exception e) { note = "Couldn't open it: " + e.Message; RenderStatus(); }
    }

    async Task Load(string id)
    {
        try
        {
            var j = await tt.Get(id);
            if (loaded == null) Fill(j);   // a slower, earlier load of the same task mustn't reset what you've typed since
        }
        catch (NeedsSignIn) { EditStatus.Text = "TickTick signed you out: go back and sign in to edit"; }
        catch (Exception e) { EditStatus.Text = "Couldn't load it from TickTick: " + e.Message; }
    }

    /// Shows the task as TickTick has it now. Fields stay read-only until this, so nothing typed meanwhile is lost.
    void Fill(JsonObject j)
    {
        if (editing == null || (string?)j["id"] != editing.Id) return;
        loaded = j;
        var checklist = (string?)j["kind"] == "CHECKLIST";
        EditTitle.Text = (string?)j["title"] ?? "";
        EditContent.Text = (string?)j[checklist ? "desc" : "content"] ?? "";
        editPriority = (int?)j["priority"] ?? 0;
        editItems = j["items"]?.AsArray().OfType<JsonObject>().Select(x => (JsonObject)x.DeepClone()).ToList() ?? new();
        AddItemRow.Visibility = checklist ? Visibility.Visible : Visibility.Collapsed;
        CompleteButton.Visibility = (int?)j["status"] is 0 or null ? Visibility.Visible : Visibility.Collapsed;
        RenderPriority();
        RenderItems();
        Editable(true);
        EditStatus.Text = (int?)j["status"] is 0 or null ? "" : "Completed";
        EditContent.Focus();
        EditContent.CaretIndex = EditContent.Text.Length;
    }

    void Editable(bool on)
    {
        EditTitle.IsReadOnly = EditContent.IsReadOnly = AddItem.IsReadOnly = !on;
        SaveButton.IsEnabled = CompleteButton.IsEnabled = on;
        PriorityRow.IsEnabled = ItemsPanel.IsEnabled = on;
    }

    void RenderPriority()
    {
        PriorityRow.Children.Clear();
        foreach (var (label, p) in new[] { ("None", 0), ("Low", 1), ("Medium", 3), ("High", 5) })
        {
            var dot = new Border { Width = 8, Height = 8, CornerRadius = new CornerRadius(4), Background = PriorityBrush(p), Margin = new Thickness(0, 0, 6, 0) };
            var content = new StackPanel { Orientation = Orientation.Horizontal };
            content.Children.Add(dot);
            content.Children.Add(new TextBlock { Text = label, FontSize = 12 });
            var b = new Button { Content = content, Padding = new Thickness(10, 4, 10, 4), Margin = new Thickness(0, 0, 6, 0),
                Background = p == editPriority ? new SolidColorBrush(Color.FromArgb(0x55, 0xFF, 0xFF, 0xFF)) : new SolidColorBrush(Color.FromArgb(0x16, 0xFF, 0xFF, 0xFF)) };
            b.Click += (_, _) => { editPriority = p; RenderPriority(); };
            PriorityRow.Children.Add(b);
        }
    }

    void RenderItems()
    {
        ItemsPanel.Children.Clear();
        foreach (var item in editItems)
        {
            var done = (int?)item["status"] is > 0;
            var c = new CheckBox
            {
                IsChecked = done, Foreground = Foreground, Margin = new Thickness(2, 3, 0, 3), VerticalContentAlignment = VerticalAlignment.Center,
                Content = new TextBlock { Text = (string?)item["title"] ?? "", TextWrapping = TextWrapping.Wrap, Opacity = done ? 0.5 : 1, TextDecorations = done ? TextDecorations.Strikethrough : null },
            };
            c.Click += (_, _) => { item["status"] = c.IsChecked == true ? 1 : 0; RenderItems(); };
            ItemsPanel.Children.Add(c);
        }
    }

    static string Lf(string? t) => (t ?? "").Replace("\r\n", "\n");

    /// Only the fields you changed, in the shape update_task wants. An item typed but not yet added with Enter counts too.
    JsonObject Changes()
    {
        var c = new JsonObject();
        if (loaded == null) return c;
        var checklist = (string?)loaded["kind"] == "CHECKLIST";
        var field = checklist ? "desc" : "content";
        if (Lf(EditTitle.Text) != Lf((string?)loaded["title"])) c["title"] = Lf(EditTitle.Text);
        if (Lf(EditContent.Text) != Lf((string?)loaded[field])) c[field] = Lf(EditContent.Text);
        if (editPriority != ((int?)loaded["priority"] ?? 0)) c["priority"] = editPriority;
        if (checklist)
        {
            var pending = AddItem.Text.Trim() is { Length: > 0 } t ? [new JsonObject { ["title"] = t, ["status"] = 0 }] : Array.Empty<JsonObject>();
            var items = new JsonArray(editItems.Concat(pending).Select((x, i) =>
            {
                var y = (JsonObject)x.DeepClone();
                y["sortOrder"] ??= i;
                return (JsonNode)y;
            }).ToArray());
            if (items.ToJsonString() != (loaded["items"]?.ToJsonString() ?? "[]")) c["items"] = items;
        }
        return c;
    }

    /// Saves what you changed and, with [complete], then marks the task done. Unless [force], it first checks the task
    /// hasn't changed in TickTick since it loaded (TickTick accepts a stale etag) and asks if it has.
    /// While it runs, Back, Esc and a second Save do nothing, so it can only ever act on the task it started with.
    async Task Save(bool force, bool complete)
    {
        if (busy || editing == null || loaded == null) return;
        var (t, l) = (editing, loaded);
        var changes = Changes();
        var projectId = (string)l["projectId"]!;
        if (changes.Count == 0 && !complete) { ShowSearch(); return; }
        if (demo)
        {
            if (complete) { tasks.RemoveAll(x => x.Id == t.Id); ShowSearch(); }
            else Ask("This is the demo, so nothing was saved.", "OK", ShowSearch, "Keep editing", () => { });
            return;
        }
        busy = true;
        Editable(false);
        Banner.Visibility = Visibility.Collapsed;
        EditStatus.Text = complete ? "Completing…" : "Saving…";
        try
        {
            if (changes.Count > 0)
            {
                if (!force)
                {
                    var now = await tt.Get(t.Id);
                    if ((string?)now["etag"] != (string?)l["etag"])
                    {
                        Editable(true);
                        EditStatus.Text = "";
                        Ask("This task changed in TickTick since you opened it. Saving puts your version of the fields you edited over theirs.",
                            "Save mine", () => _ = Save(force: true, complete), "Show theirs", () => Fill(now));
                        return;
                    }
                }
                var saved = await tt.Update(t.Id, projectId, changes);
                Replace(TickTick.Parse(saved, t.List));
                loaded = saved;   // if completing fails next, a retry compares against this version
            }
            if (complete)
            {
                await tt.Complete(projectId, t.Id);
                tasks.RemoveAll(x => x.Id == t.Id);
                SaveCache();
            }
            note = complete ? $"Completed “{t.Title}”" : "Saved";
            ShowSearch();
        }
        catch (NeedsSignIn) { Editable(true); EditStatus.Text = "TickTick signed you out. Copy your text, then go back and sign in."; }
        catch (Exception e) { Editable(true); EditStatus.Text = (complete ? "Didn't complete: " : "Didn't save: ") + e.Message; }
        finally { busy = false; }
    }

    void Replace(TTask t)
    {
        var i = tasks.FindIndex(x => x.Id == t.Id);
        if (i >= 0) tasks[i] = t;
        SaveCache();
    }

    void Back()
    {
        if (busy) return;
        if (Changes().Count > 0) Ask("You have changes that aren't saved.", "Save", () => _ = Save(force: false, complete: false), "Discard", ShowSearch);
        else ShowSearch();
    }

    void Ask(string text, string a, Action onA, string b, Action onB)
    {
        BannerText.Text = text;
        BannerA.Content = a;
        BannerB.Content = b;
        (bannerA, bannerB) = (onA, onB);
        Banner.Visibility = Visibility.Visible;
    }

    void ShowSearch()
    {
        editing = null;
        loaded = null;
        Banner.Visibility = Visibility.Collapsed;
        EditPage.Visibility = Visibility.Collapsed;
        SearchPage.Visibility = Visibility.Visible;
        Box.Focus();
        Render();
    }

    // ---------- Settings menu ----------

    void ShowMenu()
    {
        var m = new ContextMenu { PlacementTarget = GearButton };
        m.Items.Add(Choice("Sort by", new[] { ("Priority", "Priority"), ("Best match", "Best"), ("Due date", "Due"), ("Recently changed", "Modified"), ("Recently added", "Created") }, s.Sort, v => s.Sort = v));
        m.Items.Add(Choice("Matches shown", new[] { 4, 6, 8, 10 }.Select(n => (n.ToString(), n)), s.Results, v => s.Results = v));
        m.Items.Add(Choice("Open in TickTick goes to", new[] { ("TickTick for Windows, searching for the title", "App"), ("The exact task, in your browser", "Web") }, s.OpenIn, v => s.OpenIn = v));
        m.Items.Add(Choice("Shortcut", new[] { "Ctrl+Alt+F", "Ctrl+Alt+S", "Ctrl+Shift+Space", "Win+Shift+F", "Alt+Space" }.Select(k => (k, k)).Append(("None", "")),
            s.Hotkey ?? "", v => { s.Hotkey = v; note = null; RegisterHotkey(); }));
        var leaveOut = new MenuItem { Header = "Lists to leave out" };
        foreach (var name in tasks.Select(t => t.List).Concat(s.ExcludeLists).Distinct().Order())
        {
            var i = new MenuItem { Header = name, IsCheckable = true, IsChecked = s.ExcludeLists.Contains(name), StaysOpenOnClick = true };
            i.Click += (_, _) =>
            {
                if (i.IsChecked) s.ExcludeLists.Add(name); else s.ExcludeLists.Remove(name);
                Persist();
                Render();
            };
            leaveOut.Items.Add(i);
        }
        m.Items.Add(leaveOut);
        m.Items.Add(Check("Include archived lists", s.ArchivedLists, v => { s.ArchivedLists = v; _ = Sync(); }));
        m.Items.Add(Check("Start with Windows", StartsWithWindows, v => StartsWithWindows = v));
        m.Items.Add(new Separator());
        m.Items.Add(Item("Sync now (Ctrl+R)", () => _ = Sync()));
        m.Items.Add(Item("Search tips", () => { tips = true; Render(); }));
        m.Items.Add(Item("Edit settings.json", () => { Persist(); Process.Start(new ProcessStartInfo(Settings.Path) { UseShellExecute = true }); }));
        m.Items.Add(Item(update != null ? $"Update to {update.Tag}" : "Check for updates", () => _ = update != null ? Install() : CheckUpdate(quiet: false)));
        if (!demo) m.Items.Add(Item(tt.SignedIn ? "Sign out of TickTick" : "Sign in to TickTick", () =>
        {
            if (tt.SignedIn) { tt.SignOut(); tasks.Clear(); synced = DateTime.MinValue; File.Delete(CachePath); Render(); }
            else _ = SignIn();
        }));
        m.Items.Add(Item("Quit Tickfind", () => Application.Current.Shutdown()));
        m.IsOpen = true;
    }

    MenuItem Choice<T>(string header, IEnumerable<(string Label, T Value)> options, T current, Action<T> set)
    {
        var parent = new MenuItem { Header = header };
        foreach (var (label, value) in options)
        {
            var i = new MenuItem { Header = label, IsChecked = Equals(value, current) };
            i.Click += (_, _) => { set(value); Persist(); Render(); };
            parent.Items.Add(i);
        }
        return parent;
    }

    MenuItem Check(string header, bool on, Action<bool> set)
    {
        var i = new MenuItem { Header = header, IsCheckable = true, IsChecked = on };
        i.Click += (_, _) => { set(i.IsChecked); Persist(); Render(); };
        return i;
    }

    static MenuItem Item(string header, Action act)
    {
        var i = new MenuItem { Header = header };
        i.Click += (_, _) => act();
        return i;
    }

    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    static bool StartsWithWindows
    {
        get => Registry.CurrentUser.OpenSubKey(RunKey)?.GetValue("Tickfind") != null;
        set
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true)!;
            if (value) k.SetValue("Tickfind", $"\"{Environment.ProcessPath}\" --hidden");
            else k.DeleteValue("Tickfind", false);
        }
    }
}
