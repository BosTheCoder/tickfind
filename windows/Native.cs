using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace Tickfind;

/// The Win32/DWM calls WPF doesn't expose: the global shortcut, the acrylic backdrop and the work area under the mouse.
static class Native
{
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr h, int attr, ref int val, int size);
    [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr h, int id, uint mods, uint vk);
    [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr h, int id);
    [DllImport("user32.dll")] static extern bool GetCursorPos(out PointI p);
    [DllImport("user32.dll")] static extern IntPtr MonitorFromPoint(PointI p, uint flags);
    [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr m, ref MonitorInfo mi);
    [StructLayout(LayoutKind.Sequential)] struct PointI { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] struct RectI { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] struct MonitorInfo { public int Size; public RectI Monitor, Work; public uint Flags; }

    public const int WM_HOTKEY = 0x0312;

    /// Registers "Ctrl+Alt+F"-style text as a global hotkey. False if it can't be parsed or another app owns it.
    public static bool Hotkey(IntPtr hwnd, string? text)
    {
        UnregisterHotKey(hwnd, 1);
        if (string.IsNullOrWhiteSpace(text)) return true;
        uint mods = 0x4000;   // MOD_NOREPEAT
        var parts = text.Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        foreach (var p in parts[..^1])
            mods |= p.ToLower() switch { "alt" => 1u, "ctrl" or "control" => 2u, "shift" => 4u, "win" => 8u, _ => 0u };
        var key = parts[^1];
        if (key.Length == 1 && char.IsDigit(key[0])) key = "D" + key;
        if (!Enum.TryParse<System.Windows.Input.Key>(key, true, out var k)) return false;
        return RegisterHotKey(hwnd, 1, mods, (uint)System.Windows.Input.KeyInterop.VirtualKeyFromKey(k));
    }

    public static void Acrylic(IntPtr hwnd)
    {
        HwndSource.FromHwnd(hwnd).CompositionTarget.BackgroundColor = Colors.Transparent;
        int acrylic = 3, round = 2, dark = 1;
        DwmSetWindowAttribute(hwnd, 38, ref acrylic, 4);   // DWMWA_SYSTEMBACKDROP_TYPE = transient (acrylic)
        DwmSetWindowAttribute(hwnd, 33, ref round, 4);     // DWMWA_WINDOW_CORNER_PREFERENCE = round
        DwmSetWindowAttribute(hwnd, 20, ref dark, 4);      // DWMWA_USE_IMMERSIVE_DARK_MODE tints the acrylic dark
    }

    /// The work area (in WPF units) of the monitor the mouse is on, so the box opens where you're looking.
    public static Rect WorkAreaAtCursor(Visual v)
    {
        var mi = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (!GetCursorPos(out var p) || !GetMonitorInfo(MonitorFromPoint(p, 2), ref mi)) return SystemParameters.WorkArea;
        var m = PresentationSource.FromVisual(v)?.CompositionTarget?.TransformFromDevice ?? Matrix.Identity;
        return new Rect(m.Transform(new Point(mi.Work.L, mi.Work.T)), m.Transform(new Point(mi.Work.R, mi.Work.B)));
    }
}
