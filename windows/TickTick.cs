using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Tickfind;

public class NeedsSignIn : Exception { }

/// TickTick through its hosted MCP endpoint (JSON-RPC tools/call). Sign-in is OAuth with dynamic client
/// registration and PKCE, so there's no client id or secret to set up. TickTick issues no refresh token:
/// when the access token expires, the app asks you to sign in again.
public class TickTick
{
    const string Mcp = "https://mcp.ticktick.com/";
    const string Auth = "https://ticktick.com/oauth/authorize", TokenUrl = "https://api.ticktick.com/oauth/token",
        Register = "https://api.ticktick.com/oauth/register", Scope = "tasks:read tasks:write";
    static string TokenPath => Path.Combine(AppContext.BaseDirectory, "token.dat");

    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(60) };
    string? token;
    DateTime expires = DateTime.MaxValue;

    public TickTick()
    {
        try
        {
            var j = JsonNode.Parse(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(TokenPath), null, DataProtectionScope.CurrentUser)))!;
            token = (string?)j["access"];
            if ((long?)j["expires"] is long e) expires = DateTimeOffset.FromUnixTimeSeconds(e).UtcDateTime;
        }
        catch { }
    }

    public bool SignedIn => token != null && DateTime.UtcNow < expires;

    public void SignOut()
    {
        token = null;
        File.Delete(TokenPath);
    }

    public async Task SignIn()
    {
        var verifier = B64(RandomNumberGenerator.GetBytes(32));
        var state = B64(RandomNumberGenerator.GetBytes(16));
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var redirect = $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/callback";
            // Each sign-in registers a fresh public client for this exact redirect (RFC 7591).
            using var reg = await http.PostAsJsonAsync(Register, new
            {
                client_name = "Tickfind for Windows",
                redirect_uris = new[] { redirect },
                grant_types = new[] { "authorization_code" },
                response_types = new[] { "code" },
                token_endpoint_auth_method = "none",
                scope = Scope,
            });
            reg.EnsureSuccessStatusCode();
            var clientId = (string)JsonNode.Parse(await reg.Content.ReadAsStringAsync())!["client_id"]!;

            var url = Auth + "?" + Form(new()
            {
                ["client_id"] = clientId,
                ["redirect_uri"] = redirect,
                ["response_type"] = "code",
                ["scope"] = Scope,
                ["state"] = state,
                ["code_challenge"] = B64(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))),
                ["code_challenge_method"] = "S256",
                ["resource"] = Mcp,
            });
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

            // Browsers open speculative connections that never send a request, so serve every
            // connection concurrently and take the first one that carries the redirect.
            var result = new TaskCompletionSource<Dictionary<string, string>>();
            _ = Task.Run(async () =>
            {
                while (!result.Task.IsCompleted)
                {
                    TcpClient c;
                    try { c = await listener.AcceptTcpClientAsync(); } catch { return; }
                    _ = Serve(c, result);
                }
            });
            var q = await result.Task.WaitAsync(TimeSpan.FromMinutes(15));
            if (q.TryGetValue("error", out var err)) throw new Exception(err);
            if (q.GetValueOrDefault("state") != state) throw new Exception("state mismatch");

            using var r = await http.PostAsync(TokenUrl, new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "authorization_code",
                ["code"] = q["code"],
                ["redirect_uri"] = redirect,
                ["client_id"] = clientId,
                ["code_verifier"] = verifier,
                ["resource"] = Mcp,
            }));
            var body = await r.Content.ReadAsStringAsync();
            if (!r.IsSuccessStatusCode) throw new Exception($"TickTick refused the sign-in ({(int)r.StatusCode}): {body}");
            var tok = JsonNode.Parse(body)!;
            token = (string)tok["access_token"]!;
            expires = (long?)tok["expires_in"] is long secs ? DateTime.UtcNow.AddSeconds(secs - 300) : DateTime.MaxValue;
            var saved = new JsonObject { ["access"] = token, ["expires"] = expires == DateTime.MaxValue ? null : new DateTimeOffset(expires).ToUnixTimeSeconds() };
            File.WriteAllBytes(TokenPath, ProtectedData.Protect(Encoding.UTF8.GetBytes(saved.ToJsonString()), null, DataProtectionScope.CurrentUser));
        }
        finally { listener.Stop(); }
    }

    static async Task Serve(TcpClient c, TaskCompletionSource<Dictionary<string, string>> result)
    {
        using (c)
        {
            try
            {
                var stream = c.GetStream();
                var line = await new StreamReader(stream).ReadLineAsync() ?? "";   // "GET /callback?code=..&state=.. HTTP/1.1"
                var path = line.Split(' ').ElementAtOrDefault(1) ?? "";
                var q = path.Contains('?')
                    ? path[(path.IndexOf('?') + 1)..].Split('&').Select(p => p.Split('=', 2))
                        .ToDictionary(p => p[0], p => Uri.UnescapeDataString(p.ElementAtOrDefault(1) ?? ""))
                    : new();
                var hit = q.ContainsKey("code") || q.ContainsKey("error");
                var msg = q.ContainsKey("code") ? "Tickfind is signed in to TickTick. You can close this tab." : "Sign-in didn't finish. Try again from Tickfind.";
                var body = hit ? $"<h2 style='font-family:sans-serif'>{msg}</h2>" : "";
                var resp = $"HTTP/1.1 {(hit ? "200 OK" : "404 Not Found")}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {Encoding.UTF8.GetByteCount(body)}\r\nConnection: close\r\n\r\n{body}";
                await stream.WriteAsync(Encoding.UTF8.GetBytes(resp));
                if (hit) result.TrySetResult(q);
            }
            catch { }
        }
    }

    /// TickTick's MCP answers "unknown_exception" or a 5xx for a few seconds at a time, so those get one retry.
    async Task<JsonNode?> Call(string tool, object args)
    {
        for (var attempt = 0; ; attempt++)
        {
            try { return await CallOnce(tool, args); }
            catch (Exception e) when (attempt == 0 && e is not NeedsSignIn && (e is HttpRequestException or TaskCanceledException || e.Message.Contains("unknown_exception")))
            {
                await Task.Delay(1500);
            }
        }
    }

    async Task<JsonNode?> CallOnce(string tool, object args)
    {
        if (!SignedIn) throw new NeedsSignIn();
        using var req = new HttpRequestMessage(HttpMethod.Post, Mcp)
        {
            Content = JsonContent.Create(new { jsonrpc = "2.0", id = 1, method = "tools/call", @params = new { name = tool, arguments = args } }),
        };
        req.Headers.Authorization = new("Bearer", token);
        req.Headers.Accept.ParseAdd("application/json");
        req.Headers.Accept.ParseAdd("text/event-stream");
        using var r = await http.SendAsync(req);
        if (r.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden) { SignOut(); throw new NeedsSignIn(); }
        r.EnsureSuccessStatusCode();
        var text = await r.Content.ReadAsStringAsync();
        // Streamable HTTP may answer as an event stream: the JSON is on the data: line.
        if (text.StartsWith("event:") || text.StartsWith("data:"))
            text = text.Split('\n').Last(l => l.StartsWith("data:"))[5..];
        var resp = JsonNode.Parse(text)!;
        var res = resp["result"];
        if (resp["error"] is { } err) throw new Exception($"{tool}: {err["message"] ?? err}");
        var first = (string?)res?["content"]?[0]?["text"];
        if ((bool?)res?["isError"] == true) throw new Exception($"{tool}: {first}");
        // structuredContent carries the whole result; content[] is the same as text.
        var sc = res?["structuredContent"];
        if (sc is JsonObject o) return o.TryGetPropertyValue("result", out var inner) ? inner : o;
        return first == null ? null : JsonNode.Parse(first);
    }

    /// Every open task and note in every open list (and archived ones if asked), read list by list in parallel.
    /// A list that can't be read is skipped and named in [Failed], so one bad list doesn't block the rest.
    public async Task<(List<TTask> Tasks, Dictionary<string, string> Lists, List<string> Failed)> Sync(bool archived)
    {
        var all = (await Call("list_projects", new { }))!.AsArray().OfType<JsonObject>().ToList();
        var names = all.ToDictionary(p => (string)p["id"]!, p => (string?)p["name"] ?? "?");
        // Calendar-synced lists ("Daily Schedule - GCAL", "Events - GCAL") are never indexed, so no search or view shows them.
        var projects = all.Where(p => (archived || (bool?)p["closed"] != true) && ((string?)p["name"])?.Contains("gcal", StringComparison.OrdinalIgnoreCase) != true).ToList();
        var gate = new SemaphoreSlim(6);
        var failed = new List<string>();
        var lists = await Task.WhenAll(projects.Select(async p =>
        {
            var name = (string?)p!["name"] ?? "?";
            await gate.WaitAsync();
            try
            {
                var data = await Call("get_project_with_undone_tasks", new { project_id = (string)p["id"]! });
                return (data?["tasks"]?.AsArray() ?? new JsonArray()).OfType<JsonObject>().Select(t => Parse(t, name)).ToList();
            }
            catch (Exception e) when (e is not NeedsSignIn)
            {
                lock (failed) failed.Add(name);
                return new List<TTask>();
            }
            finally { gate.Release(); }
        }));
        // The inbox's real id (inbox123…) is only on its tasks; list_projects calls it "inbox".
        foreach (var t in lists.SelectMany(l => l).Where(t => t.List == "Inbox")) names.TryAdd(t.ProjectId, "Inbox");
        return (lists.SelectMany(l => l).ToList(), names, failed);
    }

    public async Task<JsonObject> Get(string id) => (JsonObject)(await Call("get_task_by_id", new { task_id = id }))!;

    /// Sends only [changes]; TickTick keeps every field it isn't sent (dates, reminders, tags).
    public async Task<JsonObject> Update(string id, string projectId, JsonObject changes)
    {
        changes["id"] = id;
        changes["projectId"] = projectId;
        return (JsonObject)(await Call("update_task", new { task_id = id, task = changes }))!;
    }

    public Task Complete(string projectId, string id) => Call("complete_task", new { project_id = projectId, task_id = id });

    /// TickTick's own server search, which also covers completed tasks.
    public async Task<List<(string Id, string Title, string ProjectId)>> ServerSearch(string query)
    {
        var res = await Call("search_task", new { query });
        var items = (res?["results"] ?? res)?.AsArray() ?? new JsonArray();
        return items.OfType<JsonObject>().Select(t =>
        {
            var url = (string?)t["url"] ?? "";
            var m = Regex.Match(url, "#p/([^/]+)/tasks/");
            var title = (string?)t["title"] ?? "";
            if (Regex.Match(title, @"^\[(.+)\]\(.*\)$") is { Success: true } link) title = link.Groups[1].Value;   // a task that's just a link
            return ((string)t["id"]!, title.Trim(), m.Success ? m.Groups[1].Value : "");
        }).ToList();
    }

    public static TTask Parse(JsonObject t, string list) => new(
        (string)t["id"]!, (string?)t["projectId"] ?? "", ((string?)t["title"] ?? "").Trim(),
        string.Join("\n", new[] { (string?)t["content"], (string?)t["desc"] }.Where(s => !string.IsNullOrEmpty(s))),
        (string?)t["kind"] ?? "TEXT", (int?)t["priority"] ?? 0,
        t["tags"]?.AsArray().Select(x => (string)x!).ToArray() ?? [],
        t["items"]?.AsArray().Select(x => (string?)x?["title"] ?? "").ToArray() ?? [],
        Date(t["dueDate"]), Date(t["modifiedTime"]) ?? DateTimeOffset.MinValue, Date(t["createdTime"]) ?? DateTimeOffset.MinValue, list);

    /// TickTick writes offsets as +0000, which DateTimeOffset won't parse without the colon.
    public static DateTimeOffset? Date(JsonNode? n)
    {
        var s = (string?)n;
        if (string.IsNullOrEmpty(s)) return null;
        s = Regex.Replace(s, @"([+-]\d\d)(\d\d)$", "$1:$2");
        return DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : null;
    }

    public static string WebUrl(string projectId, string id) => $"https://ticktick.com/webapp/#p/{projectId}/tasks/{id}";

    static string Form(Dictionary<string, string> kv) => string.Join("&", kv.Select(p => $"{p.Key}={Uri.EscapeDataString(p.Value)}"));
    static string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
