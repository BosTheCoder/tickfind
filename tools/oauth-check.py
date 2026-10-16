"""Checks TickTick's sign-in the way both apps do it (dynamic client registration + PKCE), without the apps.

    python3 tools/oauth-check.py start            # prints the URL to open and sign in with
    python3 tools/oauth-check.py finish '<url>'   # the URL the browser was sent back to (it needn't load)

finish exchanges the code, makes one MCP call and prints what came back. The token is not stored.
"""
import base64, hashlib, json, os, secrets, sys, tempfile, urllib.parse, urllib.request

STATE = os.path.join(tempfile.gettempdir(), "tickfind-oauth-check.json")
REDIRECT = "http://127.0.0.1:53682/callback"
MCP = "https://mcp.ticktick.com/"


def post(url, data, form=False):
    body = urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/x-www-form-urlencoded" if form else "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"{url}: HTTP {e.code} {e.read().decode()[:500]}")


def start():
    reg = post("https://api.ticktick.com/oauth/register", {
        "client_name": "Tickfind sign-in check", "redirect_uris": [REDIRECT], "grant_types": ["authorization_code"],
        "response_types": ["code"], "token_endpoint_auth_method": "none", "scope": "tasks:read tasks:write"})
    verifier = secrets.token_urlsafe(32)
    st = {"client_id": reg["client_id"], "verifier": verifier, "state": secrets.token_urlsafe(16)}
    json.dump(st, open(STATE, "w"))
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    print("https://ticktick.com/oauth/authorize?" + urllib.parse.urlencode({
        "client_id": st["client_id"], "redirect_uri": REDIRECT, "response_type": "code", "scope": "tasks:read tasks:write",
        "state": st["state"], "code_challenge": challenge, "code_challenge_method": "S256", "resource": MCP}))


def finish(url):
    st = json.load(open(STATE))
    q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(url).query))
    if q.get("state") != st["state"]:
        sys.exit(f"state mismatch: {q}")
    tok = post("https://api.ticktick.com/oauth/token", {
        "grant_type": "authorization_code", "code": q["code"], "redirect_uri": REDIRECT,
        "client_id": st["client_id"], "code_verifier": st["verifier"], "resource": MCP}, form=True)
    print("token response keys:", sorted(tok), "| expires_in:", tok.get("expires_in"), "| scope:", tok.get("scope"))
    req = urllib.request.Request(MCP, data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
        "params": {"name": "list_projects", "arguments": {}}}).encode(), headers={
        "Authorization": f"Bearer {tok['access_token']}", "Accept": "application/json, text/event-stream", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        res = json.load(r)["result"]
    print("list_projects:", len(res["structuredContent"]["result"]), "lists")
    os.remove(STATE)


if __name__ == "__main__":
    start() if sys.argv[1] == "start" else finish(sys.argv[2])
