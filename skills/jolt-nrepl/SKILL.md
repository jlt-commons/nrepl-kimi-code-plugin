---
name: jolt-nrepl
description: Use a persistent Jolt nREPL session for evaluation, reloading, runtime inspection, and tests
type: prompt
whenToUse: When working with Jolt or Clojure code that can be evaluated through a running Jolt nREPL server
---

Use the `jolt` MCP server's nREPL tools for Jolt runtime work. The session persists across calls, so vars, namespaces, and loaded dependencies survive until the server restarts or you disconnect.

Always pass the current workspace's absolute path as `cwd`. The MCP server runs from its plugin directory, not from the user's project. If a call reports a project mismatch, call `nrepl_connect` with the new project root instead of evaluating in the old project.

## Start and connect

If no server is running, start one from the project root and leave it running:

```bash
jolt nrepl-server 0
```

Port `0` selects a free port. Wait for `.nrepl-port`, then call `nrepl_eval`; it discovers the port and connects automatically. Use `nrepl_connect` only for an explicit host/port, to switch projects, or to reconnect deliberately. Use a clone's `bin/jolt` when the project uses a source checkout instead of an installed `jolt`.

## Develop in the live session

- Prefer `nrepl_load_file` after batch edits. Pass a project-relative `file` path.
- For targeted namespace reloads, evaluate `(require '[my.namespace :as ns] :reload)`.
- Evaluate forms and inspect vars with `nrepl_eval`; multiple values, stdout, stderr, and current namespace are returned.
- Run tests in the existing session, for example `(require '[clojure.test :as t]) (t/run-tests 'my.namespace-test)`.
- Check `nrepl_status` when connection ownership or interrupt capability matters.
- Use `nrepl_disconnect` to close the session explicitly. It also tears down an active client request without replaying it.

Jolt runs on Chez Scheme, not the JVM. Do not rely on Java reflection, classloaders, JVM heap behavior, or other JVM-only assumptions. Jolt's Clojure coverage is broad but has documented divergences.

## Optional rich middleware

Jolt's built-in server supports eval and load-file but not interrupt. For interruptible sessions and other standard middleware, add this to the project's `deps.edn`:

```clojure
{:deps {jolt-lang/nrepl {:git/url "https://github.com/jolt-lang/nrepl"
                         :git/tag "v0.4.0"
                         :git/sha "16558b920864e5302a5c1433a78f977bbb64a9d1"}}
 :nrepl/middleware [nrepl.middleware/default-middleware]}
```

Then restart `jolt nrepl-server`. The full `cider.nrepl/cider-middleware` stack is optional and is not required by this plugin.

`nrepl_interrupt` works only when `describe` advertises `interrupt`. Interrupt stops compute-bound evals at a safe point; it cannot preempt a foreign call until that call returns. A timeout without confirmed interrupt does not prove that server-side code stopped. Never assume a timed-out form had no side effects, and never replay it automatically.
