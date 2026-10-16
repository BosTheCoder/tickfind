using System.IO;
using System.Windows;

namespace Tickfind;

public partial class App : Application
{
    static Mutex? single;
    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool AllowSetForegroundWindow(int pid);

    protected override void OnStartup(StartupEventArgs e)
    {
        var demo = e.Args.Contains("--demo");
        var name = demo ? "Tickfind.Demo" : "Tickfind.SingleInstance";
        single = new Mutex(false, name);
        bool fresh;
        // After an update the new exe starts while the old one is still closing, so it waits for it rather than deferring to it.
        try { fresh = single.WaitOne(e.Args.Contains("--updated") ? 15000 : 0); }
        catch (AbandonedMutexException) { fresh = true; }
        // Launching Tickfind again (Start menu, a shortcut, a launcher) opens the running copy's search box.
        var reveal = new EventWaitHandle(false, EventResetMode.AutoReset, name + ".Reveal");
        // This copy was started by you, so it may hand the foreground to the running one; else its box opens unfocused.
        if (!fresh) { AllowSetForegroundWindow(-1); reveal.Set(); Shutdown(); return; }
        Updater.CleanUp();
        DispatcherUnhandledException += (_, ex) =>
        {
            File.AppendAllText(Path.Combine(AppContext.BaseDirectory, "tickfind.log"), $"{DateTime.Now:o} {ex.Exception}\n");
            ex.Handled = true;
        };
        ShutdownMode = ShutdownMode.OnExplicitShutdown;   // the box hides rather than closes
        // --pin keeps the box open when it loses focus and --query fills it in: for screenshots.
        var qi = Array.IndexOf(e.Args, "--query");
        var w = new MainWindow(demo, e.Args.Contains("--pin"), qi >= 0 && qi + 1 < e.Args.Length ? e.Args[qi + 1] : null);
        // At login (--hidden) it waits for the shortcut; any other start shows the box straight away.
        if (!e.Args.Contains("--hidden")) w.Summon();
        // --snapshot out.png [--edit]: render the box (or the first match's editor) to a PNG and quit. Works with the screen locked.
        var si = Array.IndexOf(e.Args, "--snapshot");
        if (si >= 0 && si + 1 < e.Args.Length) w.Snapshot(e.Args[si + 1], e.Args.Contains("--edit"));
        new Thread(() => { while (reveal.WaitOne()) w.Dispatcher.Invoke(w.Summon); }) { IsBackground = true }.Start();
    }
}
