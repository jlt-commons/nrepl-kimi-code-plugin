# Jolt nREPL for Kimi Code

A Kimi Code plugin that evaluates Clojure code in a persistent Jolt nREPL session. The plugin itself is written in Jolt and uses [`jolt-lang/nrepl`](https://github.com/jolt-lang/nrepl) for its direct bencode/TCP client.

## Features

- Lazy connection from the project's `.nrepl-port`
- One cloned session with persistent vars, namespaces, and loaded dependencies
- Evaluation with values, stdout, stderr, namespace, and error status
- Project-contained `load-file` support
- Missing-delimiter repair with an explicit notice
- Timeouts, forced disconnect, and no automatic replay after transport failure
- Interrupt when the server advertises interrupt middleware
- A session-start skill with Jolt development and testing workflows

The plugin does not start or own your Jolt nREPL server. Start it in the project and leave it running:

```bash
jolt nrepl-server 0
```

Port `0` asks the OS for a free port and writes the selected port to `.nrepl-port`.

## Requirements

- Jolt 0.8.13 or newer on `PATH`
- Kimi Code with plugin and MCP support
- Network access and Jolt's normal dependency tools for the first dependency resolution

From this directory, prefetch the pinned dependencies:

```bash
jolt -P
```

## Install

Install the local directory using its absolute path:

```text
/plugins install /Users/yogthos/src/jlt-commons/kimi-nrepl-plugin
```

Then run `/reload` or `/new`. Kimi runs the plugin from its managed copy, so reinstall after changing this source directory.

## Tools

All eval/connect/load-file calls require the workspace's absolute `cwd`; the MCP process itself runs from the plugin directory.

| Tool | Arguments | Purpose |
|---|---|---|
| `nrepl_connect` | `cwd`, optional `host`, `port` | Connect or switch projects; reads `.nrepl-port` when `port` is omitted |
| `nrepl_eval` | `cwd`, `code`, optional `timeoutMs` | Evaluate in the persistent session, connecting lazily if needed |
| `nrepl_load_file` | `cwd`, `file`, optional `timeoutMs` | Load a real file contained under the project root |
| `nrepl_status` | none | Show project, session, busy state, and interrupt capability |
| `nrepl_interrupt` | none | Request interruption of the active eval when supported |
| `nrepl_disconnect` | none | Close the session and socket; idempotent |

`timeoutMs` defaults to 120000 and accepts 1–300000. On timeout, the client requests interrupt when available, waits up to one second for the final response, and closes its connection so late messages cannot contaminate another eval. Without confirmed interrupt, server-side termination is uncertain.

A second eval while one is active returns a busy error. Connect is also rejected while busy. Disconnect is the explicit escape hatch: it tears down the active request and never replays the form.

## Optional interrupt middleware

Jolt's built-in handler supports `clone`, `describe`, `eval`, `load-file`, and `close`. Add the rich middleware to the project's `deps.edn` for interrupt and other standard operations:

```clojure
{:deps {jolt-lang/nrepl {:git/url "https://github.com/jolt-lang/nrepl"
                         :git/tag "v0.4.0"
                         :git/sha "16558b920864e5302a5c1433a78f977bbb64a9d1"}}
 :nrepl/middleware [nrepl.middleware/default-middleware]}
```

Restart `jolt nrepl-server` after changing dependencies. Interrupt works at safe points in compute-bound code; it cannot preempt a foreign call until that call returns.

## Development and verification

```bash
jolt -P
jolt -M:test
```

The suite covers delimiter repair, JSON-RPC/MCP handling, real base and rich nREPL servers, persistent eval, load-file, errors, Unicode, timeout, interrupt, disconnect, restart/no-replay behavior, and the actual MCP stdio process.

## Troubleshooting

- **No `.nrepl-port`:** start `jolt nrepl-server 0` in the intended project and wait for the file.
- **Project mismatch:** call `nrepl_connect` with the new absolute `cwd`; eval will not silently reuse another project's session.
- **Interrupt unsupported:** install `nrepl.middleware/default-middleware` and restart the server. A closed client socket alone does not prove the server-side form stopped.
- **Dependency startup failure:** run `jolt -P` in the plugin directory and inspect Jolt's normal dependency/network diagnostics.
- **Source changes do not appear in Kimi:** reinstall the plugin, then `/reload` or `/new`, because Kimi uses its managed copy.
