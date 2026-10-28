# Jolt nREPL Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kimi Code plugin whose Jolt-native MCP server provides persistent evaluation against built-in or middleware-rich Jolt nREPL servers.

**Architecture:** A small Jolt JSON-RPC/MCP stdio process dispatches tools to a session-owning client built on `jolt-lang/nrepl`. Blocking evals run on futures so the same process can answer status, interrupt, and disconnect; all stdout writes are serialized JSON-RPC messages.

**Tech Stack:** Jolt 0.8.13+, Clojure, `jolt-lang/nrepl` v0.4.0, `clojure.data.json`, `jolt-lang/time`, `clojure.test`, Kimi plugin manifests and MCP stdio.

**Spec:** `docs/superpowers/specs/2026-10-28-jolt-nrepl-plugin-design.md`

## Global Constraints

- The MCP server runs as `jolt run -m jolt-nrepl.server` with the plugin root as its process cwd; user projects are identified only by explicit absolute `cwd` tool arguments.
- Use `nrepl.core` and `nrepl.transport` for nREPL; do not fork or reimplement bencode/TCP.
- Reserve stdout for MCP messages, log diagnostics to stderr, and serialize every write.
- Default eval timeout is 120000 ms, maximum 300000 ms, manifest tool timeout 310000 ms, and timeout grace period 1000 ms.
- Never replay an eval after a mid-eval transport failure; reject eval when the connected project root differs from the supplied root.
- Interrupt only when `describe` advertises it. Do not claim foreign-call or unsupported evals stopped.
- Dependencies are pinned in `deps.edn`; use `jolt-lang/nrepl` tag v0.4.0 at full SHA `16558b920864e5302a5c1433a78f977bbb64a9d1`, `jolt-lang/time` at full SHA `19bf6a7f571d173e432f1ac26a2b5ed5830ef1cf`, and `org.clojure/data.json` version `2.5.0`.
- Do not run Git commits: the source directory is not a Git repository and no commit was requested.

## Review Focus

- Malformed JSON, JSON-RPC notifications, and unknown methods: return spec-correct errors or no response, never crash or answer a notification; pinned by Task 2 protocol tests.
- Symlinks and `..` in load-file paths: only files whose real paths remain under the real project root are readable; pinned by Task 3 path tests.
- Concurrent MCP requests and non-ASCII output: responses have intact matching ids and UTF-8 text, with no interleaved stdout; pinned by Task 5 stdio tests.
- Late responses after timeout/disconnect and server restart: old messages never join a new request and failed forms are never replayed; pinned by Task 4 lifecycle tests.
- Rich middleware interrupt versus foreign-call blocking: compute-bound evals interrupt cleanly, while unsupported/blocking cases report uncertainty honestly; pinned by Task 4 middleware tests.

---

### Task 1: Project Scaffold and Delimiter Repair

**Files:**
- Create: `deps.edn`
- Create: `kimi.plugin.json`
- Create: `src/jolt_nrepl/delimiter_repair.clj`
- Create: `test/jolt_nrepl/delimiter_repair_test.clj`
- Create: `test/jolt_nrepl/test_runner.clj`

**Interfaces:**
- Produces: `(jolt-nrepl.delimiter-repair/repair code) -> {:code string :repaired? boolean}`. `:code` is the original or repaired source and is the only input later passed to eval.
- Produces: `jolt -M:test` runs all current tests and exits nonzero on failure.

- [ ] **Step 1: Write failing delimiter tests**

Cover valid unchanged code; missing `)`, `]`, and `}` in reverse stack order; delimiters in line comments and strings; escaped backslashes/quotes; character literals such as `\(` and `\]`; mismatched or extra closers left unchanged; and non-ASCII source. Assert both returned keys for each case.

- [ ] **Step 2: Run the tests to verify the failure**

Run: `jolt -M:test`
Expected: FAIL because `jolt-nrepl.delimiter-repair` does not exist.

- [ ] **Step 3: Add the pinned project and test configuration**

In `deps.edn`, declare `:paths ["src"]`, the pinned nREPL git dependency, `org.clojure/data.json`, and `jolt-lang/time`, plus `:aliases {:test {:extra-paths ["test"] :main-opts ["-m" "jolt-nrepl.test-runner"]}}`. In the runner, require test namespaces, call `clojure.test/run-tests`, and exit with `(System/exit 1)` when fail+error is nonzero.

In `kimi.plugin.json`, declare plugin id `jolt-nrepl`, version `0.1.0`, skill path `./skills/`, session-start skill `jolt-nrepl`, and MCP server `jolt` with command `jolt`, args `["run" "-m" "jolt-nrepl.server"]`, cwd `./`, `startupTimeoutMs` 120000, and `toolTimeoutMs` 310000.

- [ ] **Step 4: Implement `repair`**

Use a byte/codepoint-safe character scan with explicit string, escape, line-comment, and character-literal states and a delimiter stack. Append closers only for a valid nonempty open stack; return the original code for unexpected/mismatched closers.

- [ ] **Step 5: Run delimiter tests and dependency resolution**

Run: `jolt -P && jolt -M:test`
Expected: dependencies resolve and all delimiter tests PASS.

### Task 2: MCP JSON-RPC Protocol Core

**Files:**
- Create: `src/jolt_nrepl/mcp.clj`
- Create: `src/jolt_nrepl/server.clj`
- Create: `test/jolt_nrepl/mcp_test.clj`
- Modify: `test/jolt_nrepl/test_runner.clj`

**Interfaces:**
- Produces: `(jolt-nrepl.mcp/handle-message message tool-definitions call-tool) -> response-map-or-nil`; `call-tool` has signature `(fn [tool-name arguments] mcp-tool-result)`.
- Produces: `(jolt-nrepl.mcp/tool-result data) -> {:content [{:type "text" :text string}] :structuredContent data :isError boolean}`; normal results default `:isError false` and failures set it true.
- Produces: `(jolt-nrepl.server/-main [& args])`, initially capable of initialize/ping/tools-list with an injectable tool table completed in Task 5.
- Consumes: no nREPL client functions yet.

- [ ] **Step 1: Write failing pure protocol tests**

Assert `initialize` returns protocolVersion, `capabilities.tools`, and `serverInfo`; `notifications/initialized` returns nil; `ping` returns `{}`; `tools/list` preserves JSON Schema definitions; `tools/call` forwards exact name/arguments; malformed JSON maps to -32700 with id nil; unknown methods map to -32601; invalid requests map to -32600; tool exceptions become `isError: true` tool results rather than JSON-RPC internal errors. Include numeric and string request ids.

- [ ] **Step 2: Run protocol tests to verify failure**

Run: `jolt -M:test`
Expected: FAIL because `jolt-nrepl.mcp` does not exist.

- [ ] **Step 3: Implement the protocol core**

Support protocol versions `2024-11-05`, `2025-03-26`, and `2025-06-18`, selecting a supported requested version or returning the newest supported version for negotiation. Keep JSON object keys as strings at the wire boundary and convert tool argument objects to keyword-keyed maps only in the server adapter. Return no response for notifications.

- [ ] **Step 4: Implement the stdio entry point**

Read newline-delimited messages with `read-line`, process each through `handle-message`, and serialize responses using one locked writer function. Spawn request handling on futures so a later Task 5 eval cannot block interrupt/disconnect. On EOF, run a supplied cleanup callback and `shutdown-agents`.

- [ ] **Step 5: Run all tests**

Run: `jolt -M:test`
Expected: protocol and delimiter suites PASS; no protocol logs appear on stdout in test fixtures.

### Task 3: Persistent Client, Base Eval, and Load-File

**Files:**
- Create: `src/jolt_nrepl/client.clj`
- Create: `test/jolt_nrepl/nrepl_client_test.clj`
- Create: `test/jolt_nrepl/integration_test.clj`
- Modify: `test/jolt_nrepl/test_runner.clj`

**Interfaces:**
- Produces: `(jolt-nrepl.client/create-state) -> atom` containing disconnected, non-busy connection state.
- Produces: `(connect! state {:cwd string :host optional-string :port optional-int}) -> connection-status-map`.
- Produces: `(evaluate! state {:cwd string :code string :timeout-ms optional-int}) -> eval-result-map`; keys are `:values`, `:result`, `:out`, `:err`, `:ns`, `:status`, optional `:repaired`, and `:error?`.
- Produces: `(load-file! state {:cwd string :file string :timeout-ms optional-int}) -> eval-result-map`.
- Produces: `(status state)`, `(interrupt! state)`, and `(disconnect! state)` maps for the MCP adapter.
- Consumes: Task 1 `repair`; Task 4 adds timeout/interrupt lifecycle behavior to these same signatures.

- [ ] **Step 1: Write failing validation and path tests**

Cover missing/blank/invalid `.nrepl-port`, relative cwd, explicit host/port, timeout bounds 1–300000, project-root mismatch, and busy connection replacement. For load-file, cover relative paths, `..` escape, and a symlink inside the project pointing outside; only real paths under the real cwd may pass.

- [ ] **Step 2: Write the failing base-server integration test**

Spawn `jolt nrepl-server 0` in a temporary project and wait for `.nrepl-port`. Assert clone/describe status; eval `(+ 1 2 3)` returns `6`; a `def` survives to the next eval; stdout/stderr and namespace are captured; missing delimiters are repaired and reported; an eval error is returned without killing the session; and `load-file!` loads a temporary namespace and allows calling its function. Stop the server and remove the temporary tree in fixtures.

- [ ] **Step 3: Run client tests to verify failure**

Run: `jolt -M:test`
Expected: FAIL because `jolt-nrepl.client` does not exist; fixtures must still clean up the spawned server.

- [ ] **Step 4: Implement connection and evaluation**

Canonicalize cwd with `jolt.fs/real-path`; lazily connect from its `.nrepl-port`; create a cloned session and cache `describe` ops. Use unique request ids and `nrepl.core/message`, require a matching `done` status, combine responses without losing value order, and clear state on transport failure. Use an atomic busy guard so eval/load-file are exclusive and connect cannot replace a busy socket.

- [ ] **Step 5: Implement safe load-file and connection controls**

Resolve and canonicalize the source file with `jolt.fs`, read it only after containment checks, and send `load-file` with `:file`, `:file-path`, and `:file-name`. Implement idempotent disconnect, status including project/host/port/session/busy/interrupt support, and base-server unsupported interrupt responses.

- [ ] **Step 6: Run base client and regression tests**

Run: `jolt -M:test`
Expected: all validation, base integration, protocol, and delimiter tests PASS.

### Task 4: Timeout, Interrupt, Disconnect, and Reconnect Lifecycle

**Files:**
- Modify: `src/jolt_nrepl/client.clj`
- Create: `test/jolt_nrepl/lifecycle_test.clj`
- Modify: `test/jolt_nrepl/test_runner.clj`

**Interfaces:**
- Preserves: all Task 3 public signatures and result keys.
- Produces: timeout failures with ex-data `{:type :timeout :interrupted? boolean :certain? boolean}` for the MCP adapter to word honestly.
- Consumes: `nrepl.transport/send` for manual/timeout interrupt and Jolt future cancellation for stuck client reads.

- [ ] **Step 1: Write failing rich-middleware interrupt tests**

Start a temporary project using the pinned `jolt-lang/nrepl` dependency and only `nrepl.middleware/default-middleware`. Start a compute-bound eval, assert `interrupt!` sends the active request id, and assert the eval ends with an interrupted status/error and the same session can evaluate a follow-up form. Document separately that foreign-call blocking is not preempted; do not use sleep as the successful interrupt fixture.

- [ ] **Step 2: Write failing timeout and transport lifecycle tests**

Against the base server, assert timeout reports interrupt unsupported and uncertain server-side termination, closes the client connection, and permits a later fresh connection without replay. Against the rich server, assert timeout requests interrupt and uses the 1000 ms grace period. Cover disconnect during eval, a second eval receiving busy, connect receiving busy, late responses not contaminating a new connection, and restart requiring a fresh eval rather than replaying the failed form.

- [ ] **Step 3: Run lifecycle tests to verify failure**

Run: `jolt -M:test`
Expected: new lifecycle cases FAIL while existing suites remain green; all spawned servers are cleaned up.

- [ ] **Step 4: Implement bounded operations and interrupt**

Run blocking `nrepl.core/message` work on a future and deref with the requested timeout. On timeout, send interrupt only when advertised, wait up to 1000 ms, cancel the client future if still blocked, close/clear transport state, and return/throw the structured timeout outcome. Manual disconnect forcibly closes active transport and causes the originating eval to fail without retry.

- [ ] **Step 5: Run the complete suite repeatedly**

Run: `jolt -M:test && jolt -M:test`
Expected: two consecutive full-suite PASS runs with no leaked `.nrepl-port` files, servers, or hanging futures.

### Task 5: Kimi Tool Wiring, Skill, Documentation, and Deliverable Verification

**Files:**
- Modify: `src/jolt_nrepl/server.clj`
- Create: `test/jolt_nrepl/mcp_stdio_test.clj`
- Create: `skills/jolt-nrepl/SKILL.md`
- Create: `README.md`
- Modify: `test/jolt_nrepl/test_runner.clj`

**Interfaces:**
- Produces: MCP tools `nrepl_connect`, `nrepl_disconnect`, `nrepl_status`, `nrepl_eval`, `nrepl_load_file`, and `nrepl_interrupt`, exposed by server `jolt` with their design-specified schemas.
- Consumes: every Task 3/4 client function and Task 2 protocol function without changing their signatures.

- [ ] **Step 1: Write failing MCP stdio tests**

Spawn the real `jolt run -m jolt-nrepl.server` process. Exchange newline JSON for initialize, initialized notification, tools/list, and tools/call against a real base nREPL project. Assert exact six-tool list, argument validation, structured eval results, matching response ids, valid non-ASCII JSON, concurrent eval/status responses with no interleaving, and disconnect/EOF cleanup. Include one rich-server interrupt call through MCP.

- [ ] **Step 2: Run stdio tests to verify failure**

Run: `jolt -M:test`
Expected: FAIL because the real tool table/dispatch is not wired; child processes are cleaned up.

- [ ] **Step 3: Wire tools and format results**

Adapt JSON arguments to the client signatures, validate required fields before client calls, and convert data/exceptions to Task 2 `tool-result` maps. Text summaries include values, stdout, stderr, namespace, repair notices, project mismatch guidance, and honest interrupt/timeout wording. Eval/load-file dispatch runs asynchronously while status/interrupt/disconnect remain callable.

- [ ] **Step 4: Add the Jolt skill and installation guide**

The skill must always require absolute workspace cwd, show `jolt nrepl-server 0`, explain optional `jolt-lang/nrepl` default middleware, prefer load-file after batch edits, use `:reload` and `clojure.test`, note no JVM, and warn about optional/foreign-call interrupt. README documents Jolt 0.8.13+, `jolt -P`, tests, `/plugins install <absolute-path>`, `/reload` or `/new`, tool contracts, and troubleshooting.

- [ ] **Step 5: Verify the exact deliverable**

Run: `jolt -P && jolt -M:test`
Expected: full suite PASS, including real MCP stdio and both Jolt nREPL server variants.

Run: `jolt run -m jolt-nrepl.server` under the stdio test harness once more from the final tree.
Expected: initialize, tools/list, live eval, rich interrupt, and disconnect PASS with stdout containing only JSON-RPC.

Report manual installation activation separately unless Kimi installation is explicitly exercised: `/plugins install /Users/yogthos/src/jlt-commons/kimi-nrepl-plugin`, then `/reload` or `/new`.
