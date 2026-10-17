// PS4 Builder proxy (Cloudflare Worker). Holds the GitHub token as a SECRET and forwards only the calls the app needs,
// only for ONE repository. The app never sees the token.
//
// Settings (Cloudflare dashboard > Worker > Settings > Variables):
//   GITHUB_TOKEN  (Secret)  fine-grained token for that single repository: Contents = Read and write, Actions = Read and write
//   REPO          (Text)    e.g. iagamere/Ps4-AAA
//
// Allowed calls (relative to /repos/<REPO>), everything else is refused with 403:
const RULES = [
  ["GET",   /^$/],
  ["GET",   /^\/actions\/workflows\/build\.yml$/],
  ["GET",   /^\/actions\/workflows\/build\.yml\/runs$/],
  ["GET",   /^\/actions\/runs\/\d+$/],
  ["GET",   /^\/actions\/runs\/\d+\/jobs$/],
  ["POST",  /^\/actions\/runs\/\d+\/cancel$/],
  ["GET",   /^\/git\/ref\/heads\/[\w.\-\/]+$/],
  ["GET",   /^\/git\/commits\/[0-9a-f]{40}$/],
  ["POST",  /^\/git\/blobs$/],
  ["POST",  /^\/git\/trees$/],
  ["POST",  /^\/git\/commits$/],
  ["PATCH", /^\/git\/refs\/heads\/[\w.\-\/]+$/],
  ["GET",   /^\/releases\/tags\/[\w.\-]+$/],
  ["GET",   /^\/releases\/assets\/\d+$/],
];
const MAX_BODY = 60 * 1024 * 1024; // bytes

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const prefix = "/repos/" + env.REPO;
    if (!url.pathname.startsWith(prefix)) return new Response("forbidden", { status: 403 });
    const rest = url.pathname.slice(prefix.length);
    if (!RULES.some(([m, re]) => m === request.method && re.test(rest))) return new Response("forbidden", { status: 403 });
    if (Number(request.headers.get("content-length") || 0) > MAX_BODY) return new Response("too large", { status: 413 });

    const h = new Headers();
    h.set("Authorization", "Bearer " + env.GITHUB_TOKEN);          // the client's own Authorization header is never forwarded
    h.set("Accept", request.headers.get("Accept") || "application/vnd.github+json");
    h.set("X-GitHub-Api-Version", "2022-11-28");
    h.set("User-Agent", "ps4-builder-proxy");
    const ct = request.headers.get("Content-Type"); if (ct) h.set("Content-Type", ct);

    const init = { method: request.method, headers: h, redirect: "manual" };   // asset downloads answer 302: pass it on to the app
    if (request.method !== "GET") init.body = request.body;
    const r = await fetch("https://api.github.com" + url.pathname + url.search, init);
    const out = new Headers();
    for (const k of ["Content-Type", "Location", "Content-Length"]) { const v = r.headers.get(k); if (v) out.set(k, v); }
    return new Response(r.body, { status: r.status, headers: out });
  },
};
