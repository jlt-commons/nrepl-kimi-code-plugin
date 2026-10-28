# Jolt nREPL Plugin for Kimi Code

## Intent

Give Kimi Code a persistent, direct-TCP connection to a running Jolt nREPL server so the agent can evaluate forms, reload files, inspect runtime state, and run tests without starting a new Jolt process for each evaluation.

The plugin is for developers working in Jolt projects. It adapts the useful core of Dirge's Janet nREPL plugin to Kimi Code's plugin and MCP interfaces.

## Goals

- Expose agent-callable MCP tools for connect, disconnect, status, eval, load-file, and interrupt.
- Discover the port from `.nrepl-port` in the user's Jolt project and connect lazily on first eval.
- Preserve one cloned nREPL session across evaluations so vars, namespaces, and other state survive.
- Return values, stdout, stderr, namespace, and evaluation/error status in text and structured MCP results.
- Repair missing closing delimiters before eval, with an explicit repair notice.
- Support per-eval timeouts and interrupt only when the server advertises the `interrupt` op.
- Ship a Jolt-specific skill covering startup, reload, tests, load-file, and Jolt/Clojure differences.
- Verify both the protocol/client behavior and a real session with `jolt nrepl-server 0`.

## Non-goals and anti-goals

- Do not start, stop, or own the Jolt nREPL server as part of the plugin lifecycle.
- Do not use `jolt -e` or spawn a subprocess for each eval.
- Do not emulate JVM behavior or present Jolt as JVM Clojure.
- Do not claim an evaluation stopped when interrupt middleware is unavailable.
- Do not automatically retry an eval after a mid-eval connection failure; the form may already have had side effects.
- Do not build completion, lookup, debugging, formatting, or other middleware-dependent IDE features in the first version.
- Do not add Dirge-compatible slash commands in the first version. Kimi's agent-facing MCP tools are the primary interface.

## Constraints

- Kimi plugins declare MCP servers in `kimi.plugin.json`; local stdio server paths must stay within the plugin root.
- A plugin MCP server runs with the plugin root as its working directory, not the user's workspace. Tools therefore accept an absolute `cwd` for the Jolt project, and the skill instructs the agent always to supply the current workspace root.
- Jolt's built-in nREPL handler implements `clone`, `describe`, `eval`, `load-file`, and `close`. Interrupt and other heavier operations are middleware-dependent.
- The user replaced the earlier Node.js choice with a Jolt implementation after confirming that Kimi can launch plugin MCP servers written in any language.
- Use `jolt-lang/nrepl` v0.4.0 for the nREPL client and transport. It provides bencode, persistent connections, sessions, streaming response collection, and interruptible socket reads. Jolt 0.8.13 or newer is required by that library.
- Use `clojure.data.json` for MCP JSON-RPC and `jolt-lang/time` for its `java.time` load-time requirement. Dependencies are pinned in `deps.edn` and prefetched with `jolt -P` before local installation.
- The source directory is not currently a Git repository, so the design cannot be committed as part of this step.

## Approaches

### Zero-dependency Python MCP server

A Python standard-library server would implement stdio MCP framing and direct bencode/TCP itself. It would be self-contained and simple to install, but the project would own more protocol code and would not reuse Jolt's nREPL implementation.

### Node.js with the official MCP SDK

A Node server would delegate MCP framing to `@modelcontextprotocol/sdk` while implementing nREPL separately. This was the initial choice, but the user replaced it with Jolt so the plugin can reuse the Jolt-native nREPL client and dogfood the language it supports.

### Jolt MCP server with `jolt-lang/nrepl` — selected

A Jolt program implements the small newline-delimited JSON-RPC/MCP stdio layer and delegates nREPL wire protocol and TCP transport to `nrepl.core` and `nrepl.transport`. This keeps both the plugin and its test server in Jolt, preserves persistent sessions and interruptible reads, and avoids maintaining a second bencode client. The tradeoff is implementing the narrow MCP protocol surface directly rather than using an MCP SDK.

### CLI subprocess per evaluation

This was rejected because it loses the persistent session and direct nREPL behavior that motivate the plugin.

## Plugin shape

```text
kimi.plugin.json
deps.edn
README.md
src/jolt_nrepl/
  server.clj
  mcp.clj
  client.clj
  delimiter_repair.clj
  json.clj
skills/
  jolt-nrepl/
    SKILL.md
test/jolt_nrepl/
  delimiter_repair_test.clj
  mcp_test.clj
  nrepl_client_test.clj
  integration_test.clj
  lifecycle_test.clj
  mcp_stdio_test.clj
  test_runner.clj
docs/superpowers/specs/
  2026-10-28-jolt-nrepl-plugin-design.md
docs/superpowers/plans/
  2026-10-28-jolt-nrepl-plugin.md
```

The manifest declares a `jolt` stdio MCP server using `jolt run -m jolt-nrepl.server`, a plugin-root `cwd`, and a 310000 ms tool timeout. It loads the `jolt-nrepl` skill at session start. Tool names become `mcp__jolt__nrepl_eval`, `mcp__jolt__nrepl_connect`, and so on.

Implement the MCP stdio loop with `read-line`, `clojure.data.json/read-str`, and `clojure.data.json/write-str`. Support `initialize`, `notifications/initialized`, `ping`, `tools/list`, and `tools/call`, including JSON-RPC error responses and protocol version negotiation for the MCP versions Kimi requests. Reserve stdout exclusively for JSON-RPC messages; send diagnostics to stderr. Use `clojure.test` through a small test runner and Jolt's built-in process support for end-to-end tests.

## Tool contract

All tools return concise text plus equivalent structured content where applicable. Operational failures are MCP tool results with `isError: true`, not malformed responses or server-process exits.

### `nrepl_connect`

Input: required absolute `cwd`, optional `host` (default `127.0.0.1`), optional integer `port`.

- Read and validate `<cwd>/.nrepl-port` when `port` is absent.
- Reject the call while an eval or load-file is active; never replace the socket underneath an in-flight request.
- Otherwise replace any existing connection, clone a fresh session, and call `describe` to record advertised ops.
- Return host, port, session id, project root, and whether interrupt is available.

### `nrepl_eval`

Input: required absolute `cwd`, required nonempty `code`, optional integer `timeoutMs` from 1 to 300000 (default 120000). The 300000 ms maximum leaves time for interrupt/grace-period cleanup before Kimi's 310000 ms outer tool timeout.

- Lazily connect from `cwd` when disconnected.
- If connected to a different project root, fail clearly and instruct the agent to call `nrepl_connect` for the new root instead of evaluating in the wrong project.
- Repair only missing closing delimiters; do not silently alter code with unexpected or mismatched closers.
- Send one `eval` request in the cloned session and collect responses until `done` for that request id.
- Return all values in order, a newline-joined `result`, `out`, `err`, `ns`, statuses, and optional `repaired` code.
- Serialize eval and load-file operations. Reject a second one with a clear busy error; interrupt remains callable during an active eval.
- Reconnect automatically when already known to be disconnected, but never replay a form after a mid-eval transport failure.

### `nrepl_load_file`

Input: required absolute `cwd`, required `file` path, optional `timeoutMs` with the same bounds and default as eval.

- Resolve `file` against `cwd`, require it to remain under that project root, read it, and send Jolt's `load-file` op with file contents and path/name metadata.
- Use the same session, response collection, busy rule, and result shape as eval.
- Do not delimiter-repair a source file.

### `nrepl_status`

No input. Return connected/disconnected state and, when connected, project root, host, port, session id, busy state, and interrupt availability. A missing server or port file is a normal disconnected state, not startup noise.

### `nrepl_disconnect`

No input. Send `close` for the cloned session when possible, close the TCP socket, clear buffered/protocol state, and report the previous connection. Idempotent. As an explicit escape hatch, disconnect is allowed during an active eval: it forcibly tears down the socket, finishes that tool call with a disconnected error, and never replays the form.

### `nrepl_interrupt`

No input.

- Require a connected session and active eval/load-file request.
- If `describe` did not advertise `interrupt`, return an explicit unsupported result and do not claim cancellation.
- Otherwise send `interrupt` with the active request id and report that interruption was requested; the originating tool result reports the final status.

## Connection and protocol behavior

- Delegate bencode encoding, incremental decoding, TCP reads/writes, and interrupted reads to `jolt-lang/nrepl`. Do not fork or reimplement its codec.
- Use `nrepl.core/new-session` and `nrepl.core/message` for ordinary request/response handling, with `nrepl.transport/send` for an interrupt while an eval is active.
- Match responses by request id and ignore unrelated asynchronous messages rather than associating them with the active eval, preserving `nrepl.core/message` semantics.
- Run blocking eval/load-file calls on a separate Jolt future so the MCP loop can still serve status, interrupt, and disconnect. Serialize response writes to stdout.
- On timeout, request interrupt when available, allow a short grace period for the final response, cancel the client-side future if necessary, then close and clear the connection to prevent late responses contaminating later evals. Report that closing the client socket cannot guarantee termination of server-side code when interrupt is unsupported or blocked in a foreign call.
- On server close or malformed protocol data, clear the session and return a useful error. A later eval may make one fresh connection attempt; it must not replay the failed form.

## Delimiter repair

Scan Clojure/Jolt source while skipping line comments, strings with correct escape handling, and character literals. Track `()`, `[]`, and `{}` in order. Append missing closers only when the remaining stack is otherwise valid. Leave unmatched or mismatched closing delimiters untouched so the server reports the syntax error. Include the repaired source in the result whenever a change was made.

## Jolt skill

The session-start skill tells the agent to:

- Use these tools only for Jolt/Clojure runtime work and always pass the absolute workspace `cwd`.
- Start a server with `jolt nrepl-server 0` in the project when needed, wait for `.nrepl-port`, and leave the server running; use an installed `jolt` or a clone's `bin/jolt` as appropriate.
- Optionally add `jolt-lang/nrepl` and `nrepl.middleware/default-middleware` to the project's `deps.edn` for interrupt, completion, and lookup; the full `cider.nrepl/cider-middleware` stack is optional and not required by this plugin.
- Prefer `nrepl_load_file` after batch edits and `(require '[namespace] :reload)` for targeted reloads.
- Run `clojure.test` tests through the persistent session and inspect values/vars with eval.
- Remember Jolt uses Chez Scheme, has no JVM or Java reflection, and has some core-library divergences.
- Treat interrupt as optional middleware and never assume a timed-out form stopped, especially when it is blocked in a foreign call.

## Verification

- Rely on `jolt-lang/nrepl`'s own codec suite for bencode coverage; test only the plugin's client/session behavior rather than duplicating library tests.
- Unit-test delimiter repair across nesting, comments, escaped quotes, character literals, mismatched closers, and unchanged valid code.
- Test JSON-RPC framing, initialize/version negotiation, tool discovery, argument validation, error responses, and serialized stdout against the actual MCP stdio process.
- Run a base-server integration test by spawning `jolt nrepl-server 0` in a temporary project, waiting for `.nrepl-port`, connecting through the actual client, evaluating persistent state and output, loading a file, checking a deliberate evaluation error, verifying unsupported interrupt behavior, and closing/stopping cleanly.
- Run a rich-server integration test using `jolt-lang/nrepl` with `nrepl.middleware/default-middleware`; verify advertised interrupt support, interrupt a compute-bound eval, and confirm the session remains usable.
- Verify busy calls, project-root mismatches, timeout cleanup, disconnect during eval, no-replay behavior, and reconnect after deliberate server restart.
- Run `jolt -P`, `jolt -M:test`, and a final live MCP smoke test against the exact deliverable. Document `/plugins install <path>` followed by `/reload` or `/new` for manual Kimi verification.

## Builder decisions

The builder may choose internal namespace/function decomposition, MCP protocol-version details supported by Kimi, error wording, and test fixtures without changing the contracts above. Any simplification of project-root safety, timeout cleanup, or no-replay behavior requires revisiting the design.
