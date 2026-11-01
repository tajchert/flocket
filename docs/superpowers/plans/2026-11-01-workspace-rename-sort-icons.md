# Workspace rename, priority sort, tab numbers and agent icons: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android terminal list read like the desktop app: Priority sort by default, desktop-style tab numbers, agent icons, and renaming of spaces and tabs that is written back to the desktop's `herdr-app` layout.

**Architecture:** Pure logic goes in `core:model` (workspace ids, priority rank, agent catalog). The compare-and-set rename goes in `core:session` behind `CoreSession`, on the existing `docGet`/`docSet` calls. The UI goes in `feature:terminals:impl` (list) and `feature:terminal:impl` (tab strip). Shared visuals go in `core:ui`.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization JSON, DataStore Preferences, JUnit4, kotlinx-coroutines-test, Roborazzi/compose screenshot tests (see `android/docs/SCREENSHOT_TESTING.md`).

**Spec:** `docs/design/2026-11-01-workspace-rename-sort-icons.md` (read it first; this plan argues from it).

## Global Constraints

- Product rules in `AGENTS.md` bind every task. In particular: **3** (mutations at most once: a `doc.set` cut off by a dropped link is never resent), **5** (keep `t7` and `rohan/t7` distinct; federated layouts are read and written with `on`), **7** (no writes while a host is stale).
- Rename changes exactly one `name` string in `herdr-app` and keeps every other field, unknown ones included. The meaning must be kept, not the bytes (the core sorts keys and normalises numbers itself).
- Saving an empty (after trim) name writes `""`. That returns the space or tab to its automatic name.
- Conflict retries: **3 attempts in total**, then fail.
- Priority sections, in this order: **Needs you** (blocked, error), **Done**, **Working**, **Idle**, **No status**, **Finished**. Empty sections are hidden. Rows keep **core order** inside a section.
- Desktop rank: blocked = error = 4, done = 3, working = 2, idle = 1, other = 0.
- Unnamed tab label: `%1$d` (so `1`, `2`, …). `Space %1$d` stays.
- Agent matching: `status.app` against `match` and `id`, else the basename of the first `argv` entry that is not in the desktop's `interpreters` list, against `run`. Never match on `foreground.name`. No match → generic terminal tile.
- Licences (ADR-0002): the agent catalog is adapted from Herdr.app (Apache-2.0) and the icons come from LobeHub icons (MIT). Record both in `android/THIRD_PARTY_NOTICES.md`.
- Tests that use a real `hc` run on an isolated session `flocket-test-<random>`, are skipped when `hc` is absent, and stop the session in teardown. Never touch the developer's default session.
- Build narrowly (one module per command, from `android/`). Run `./gradlew spotlessApply` before every commit. Use Conventional Commits that end with the repo's `Co-Authored-By` line.
- Models per task (global CLAUDE.md): Sonnet for implementation tasks, **Opus for Task 2** (session concurrency and unknown outcome) and for the final whole-branch review.

## Review Focus

1. **A damaged or foreign `herdr-app` document**: `value` null, `spaces` not an array, a space with no `id`, or two spaces with the same id. Rename never throws and never writes. A repeated id gets a `layoutRef` only on its first occurrence (Task 1). `HerdrAppLayoutEdit` returns `Gone` or `NotALayout` (Task 2).
2. **The desktop renames, closes or reorders the same tab during our write**: a conflict re-reads, then re-applies by **id**, not by position. A tab that disappeared mid-retry fails as gone (Task 2 test `conflict then tab removed is gone`).
3. **Federated machine stale or unreachable**: rename of `rohan`'s space while `rohan` is `unreachable` or `needs_update` gets a clear error and makes no local write. `on` is always set from `LayoutRef.machine` (Task 2 test, Task 4 disabled state).
4. **A terminal with a reported status but an unknown app** (e.g. `status.app = "mytool"`) running under `node`: gets the generic tile, not the Node icon or a wrong agent (Task 5 test).
5. **Priority mode with a filter active and finished terminals hidden**: sections are still correct and empty ones are hidden. The host header still counts correctly (Task 3 test).

---

## Streams and order

| Wave | Stream (worktree) | Tasks | Touches |
|---|---|---|---|
| 1 | `rename-core` | 1 → 2 | `core:model` Workspace*, `core:session`, `core:model` Core.kt, `protocol/SPEC.md`, ADR |
| 1 | `sort` | 3 | `core:model` Terminal.kt + WorkspaceGrouping `topState`, `core:data`, `core:datastore`, `core:testing`, `feature:terminals:impl`, `feature:terminal:impl` strings |
| 1 | `icons-catalog` | 5a (catalog only) | new `core:model` AgentCatalog.kt, notices |
| 2 | `icons-ui` | 5b | `core:ui`, list row, tab strip |
| 3 | `rename-ui` | 4 | list screen and dialogs, tab strip |
| 4 | main | 6 | docs, full check |

Merge each stream into `main` as soon as its review is clean (`scripts/check.sh quick` green). Then build `main` once so the next worktrees start warm. Waves 2 and 3 start from the updated `main`. Task 1 and Task 3 both edit `WorkspaceGrouping.kt`, in different functions, so expect a trivial merge.

---

### Task 1: Layout ids on workspace spaces and tabs

**Files:**
- Modify: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/Workspace.kt`
- Modify: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/WorkspaceGrouping.kt`
- Test: `android/core/model/src/test/kotlin/com/mtajchert/flocket/core/model/WorkspaceGroupingTest.kt`

**Interfaces:**
- Produces (in `Workspace.kt`):
  ```kotlin
  /** A space, or a tab inside it, as it exists in a `herdr-app` document (SPEC §9.1). */
  data class LayoutRef(val machine: String?, val spaceId: String, val tabId: String? = null)
  ```
  `WorkspaceSpace.layoutRef: LayoutRef?` and `WorkspaceSpace.givenName: String` (the document's trimmed name, blank when unnamed; `name` stays the display name). `WorkspaceTab.layoutRef: LayoutRef?`. Both default to `null`/`""` so existing constructors keep compiling.

- [ ] **Step 1: Write the failing tests** in `WorkspaceGroupingTest`:
  - `` `desktop spaces and their document tabs carry layout refs` ``: a layout with `space("sp1", tab("tb1","t1"), tab("tb2","t2"), name = " web ")` gives `spaces[0].layoutRef == LayoutRef(null,"sp1")`, `givenName == "web"`, and tab refs `LayoutRef(null,"sp1","tb1")` and `LayoutRef(null,"sp1","tb2")`.
  - `` `implicit, label-only and folder tabs have no layout ref` ``: a terminal with cwd equal to the desktop space folder joins the implicit tab → that tab's `layoutRef == null`. A terminal labelled `herdr.space=sp1, herdr.tab=tbX` (where `tbX` is not in the document) → its tab has `layoutRef == null`. A label-only space and a folder space → `layoutRef == null`.
  - `` `a label tab matching a document tab id is that document tab` ``: label `herdr.tab=tb1` on a terminal missing from the layout → it lands in the tab with `layoutRef == LayoutRef(null,"sp1","tb1")`.
  - `` `machine layouts set the machine in layout refs` ``: `machineLayouts = mapOf("rohan" to layout(space("rs", tab("rt","t7"))))` with terminal `rohan/t7` → `LayoutRef("rohan","rs")` and `LayoutRef("rohan","rs","rt")`.
  - `` `blank or repeated document ids give no layout ref after the first` ``: spaces with ids `""`, `"d"`, `"d"` → refs `null`, `LayoutRef(null,"d")`, `null`. The same rule applies to tab ids within a space.
- [ ] **Step 2: Run them and see them fail.** `cd android && ./gradlew :core:model:test --tests '*WorkspaceGroupingTest*'` fails to compile (no `LayoutRef`).
- [ ] **Step 3: Implement.** In `groupScope`, a desktop space gets a ref when `ds.id` is non-blank and not seen before in this scope. Its document tabs get a ref when `dt.id` is non-blank and not seen before in that space. Track this as a flag on `TabDraft` that is set only in the step 1 (desktop) loop. Tabs created by `tab(id, …)` in step 2 (labels) or by `implicitTab()` never set the flag. `SpaceDraft.build` copies the refs and `givenName = name`.
- [ ] **Step 4: Run the module tests.** `./gradlew :core:model:test` passes.
- [ ] **Step 5: Commit** `feat(android): carry herdr-app ids on workspace spaces and tabs`.

---

### Task 2: Rename in the desktop layout (protocol, ADR, session)

**Files:**
- Modify: `protocol/SPEC.md` §9.1 (replace "read-only for us" with the narrow rename rule; add the [W] note on remote `doc.set` with `on`, and the core's normalisation of keys and numbers, from the spec's Background section)
- Create: `docs/decisions/0008-rename-in-desktop-layout.md` (context, decision, consequences; links the design doc)
- Modify: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/Core.kt` (add `DOC_GET = "doc.get"`, `DOC_SET = "doc.set"` to `CoreInfo.Methods`)
- Create: `android/core/session/src/main/kotlin/com/mtajchert/flocket/core/session/HerdrAppLayoutEdit.kt`
- Modify: `android/core/session/src/main/kotlin/com/mtajchert/flocket/core/session/CoreSession.kt`, `CoreSessionImpl.kt`
- Modify: any other `CoreSession` implementation or fake (`grep -rn ": CoreSession" android --include='*.kt'`) by adding the new method
- Test: `android/core/session/src/test/kotlin/com/mtajchert/flocket/core/session/HerdrAppLayoutEditTest.kt` (new), `CoreSessionLayoutTest.kt`, `SessionRealCoreIntegrationTest.kt`

**Interfaces:**
- Consumes: `LayoutRef` (Task 1); `CoreConnection.docGet(name, on, timeout)` and `docSet(name, value, version, on, timeout)` from `core/protocol/.../CoreCalls.kt`.
- Produces:
  ```kotlin
  object HerdrAppLayoutEdit {
      sealed interface Result {
          data class Edited(val value: JsonObject) : Result
          data object Gone : Result        // space (or tab) id not in the document
          data object NotALayout : Result  // null value, not an object, or no `spaces` array
      }
      fun rename(value: JsonElement?, spaceId: String, tabId: String?, name: String): Result
  }
  // CoreSession
  /** Sets the name of a space ([LayoutRef.tabId] null) or tab in its machine's `herdr-app` (SPEC §9.1). */
  suspend fun renameInLayout(ref: LayoutRef, name: String): AppResult<Unit>
  ```
  Failures: `AppError.Rpc(kind = NOT_FOUND)` for gone or not-a-layout; `AppError.Rpc(kind = CONFLICT)` after 3 conflicting attempts; `AppError.OutcomeUnknown("doc.set")` when the link drops after the write was sent; `AppError.ConnectionLost` when offline; core errors (`unavailable`, `machine_unknown`, `needs_update`) are passed through as `AppError.Rpc`. No new `AppError` case.

- [ ] **Step 1: Write the SPEC and ADR changes.** These are docs only. Commit them with the code at the end of the task.
- [ ] **Step 2: Write the failing `HerdrAppLayoutEditTest`:**
  - `` `renames one tab and keeps everything else` ``: load `protocol/fixtures/herdr-app-layout.json` (find how `HerdrAppLayoutParserTest` loads it). Rename one tab, then assert that the result equals the input with only `spaces[i].tabs[j].name` replaced. Compare `JsonObject`s, which also covers unknown fields, `look`, `size` and `mod` panes.
  - `` `renames a space` ``, `` `empty name writes empty string` ``, `` `name is trimmed` ``.
  - `` `unknown space or tab id is Gone` ``; `` `null, non-object or spaces-less value is NotALayout` ``.
  - `` `repeated space id edits the first only` ``.
- [ ] **Step 3: Run and see them fail.** `cd android && ./gradlew :core:session:test --tests '*HerdrAppLayoutEditTest*'`.
- [ ] **Step 4: Implement `HerdrAppLayoutEdit.rename`** with copy-on-write over `JsonObject`/`JsonArray`: rebuild only the path to the changed `name`.
- [ ] **Step 5: Write the failing session tests** in `CoreSessionLayoutTest` (script `doc.get`/`doc.set` with `FakeCore.handlers`, `errors` and `gates`, as the existing tests in that file do):
  - `` `rename sends doc set with the read version and the edited document` ``: assert the `doc.set` params `name="herdr-app"`, `version`, and that `value` has the new name.
  - `` `rename of a federated space reads and writes with on` ``: `LayoutRef("rohan", …)` → both calls carry `on="rohan"`.
  - `` `conflict re-reads and re-applies, at most 3 attempts` ``: the first two `doc.set` calls fail with `conflict` and the third succeeds → `Success`, 3 `doc.get` and 3 `doc.set` calls. With all three conflicting → `Failure(Rpc(CONFLICT))`.
  - `` `conflict then tab removed is gone` ``: the second `doc.get` returns a document without the tab → `Failure(Rpc(NOT_FOUND))`, and no second `doc.set`.
  - `` `link dropped after doc set is OutcomeUnknown and never resent` ``: follow `` `mutating call cut off after write is OutcomeUnknown and never re-sent` `` in `CoreSessionTest.kt:203`.
  - `` `remote unavailable is passed through without a write` ``: `doc.get` with `on` fails with `unavailable` → `Failure(Rpc(UNAVAILABLE))`, no `doc.set`.
- [ ] **Step 6: Implement `renameInLayout`** in `CoreSessionImpl`. The `doc.get` goes through the existing `read(...)` helper and the `doc.set` through `mutate(...)`, with `method = CoreInfo.DOC_SET`. Loop up to 3 times, and only on `AppError.Rpc(kind = CONFLICT)` from `doc.set`. Do not update `desktopLayout` locally: the core's `doc` event triggers the existing refetch.
- [ ] **Step 7: Add the real-`hc` test** to `SessionRealCoreIntegrationTest`: `` `rename writes the herdr-app document and the layout refetch shows it` ``. Seed a synthetic document with `docSet` on the isolated session, rename a tab through `CoreSession`, then wait until `state.desktopLayout` shows the new name. Follow the existing `` `a herdr-app document written by another client shows up as the desktop layout` `` test.
- [ ] **Step 8: Run.** `./gradlew :core:model:test :core:session:test` passes (the real-`hc` test runs when `hc` is installed).
- [ ] **Step 9: Commit** `feat(android): rename spaces and tabs in the desktop layout` (SPEC, ADR, code and tests together, or the docs as a separate `docs(protocol): …` commit first).

---

### Task 3: Priority sort (default) and grouped sort, persisted; tab numbers

**Files:**
- Modify: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/Terminal.kt` (add `AgentState.priorityRank`)
- Modify: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/WorkspaceGrouping.kt` (`topState()` picks by `priorityRank`) and the KDoc in `Workspace.kt`
- Modify: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/TerminalPreferences.kt` (add `enum class TerminalSort { Priority, Grouped }`)
- Modify: `android/core/data/src/main/kotlin/com/mtajchert/flocket/core/data/Repositories.kt` (`UserPreferences.terminalSort: TerminalSort = TerminalSort.Priority`), `core/data/.../internal/PreferencesRepositoryImpl.kt`
- Modify: `android/core/datastore/src/main/kotlin/com/mtajchert/flocket/core/datastore/PreferencesDataSource.kt` (key `stringPreferencesKey("terminal_list_sort")`; unknown or missing → Priority)
- Modify: `android/core/testing/src/main/kotlin/com/mtajchert/flocket/core/testing/FakeRepositories.kt` (`FakePreferencesRepository` keeps the field)
- Modify: `feature/terminals/impl/.../TerminalsLogic.kt`, `TerminalsModels.kt`, `TerminalsViewModel.kt`, `TerminalsScreen.kt`, `TerminalsEntries.kt` (if it wires actions), `src/main/res/values/strings.xml`
- Modify: `feature/terminal/impl/src/main/res/values/strings.xml` and `TerminalTabStripModel.kt`/`TerminalTabStrip.kt` KDoc (tab label `%1$d`)
- Test: `core/model/.../TerminalTest` or `WorkspaceGroupingTest` (rank), `feature/terminals/impl/src/test/.../TerminalsLogicTest.kt`, `TerminalsViewModelTest.kt`, `TerminalsComposeTest.kt`; screenshot tests in `feature/terminals/impl/src/screenshotTest` and `feature/terminal/impl`

**Interfaces:**
- Produces:
  ```kotlin
  val AgentState.priorityRank: Int   // Blocked, Error 4; Done 3; Working 2; Idle 1; Other 0
  enum class TerminalSort { Priority, Grouped }
  // feature:terminals:impl
  enum class PriorityBucket { NeedsYou, Done, Working, Idle, NoStatus, Finished }
  @Immutable data class RowPlace(
      val spaceKey: String, val spaceName: String, val spaceNumber: Int,
      val tabKey: String, val tabName: String, val tabNumber: Int,
      /** False when the space has a single tab: the place is then just the space. */
      val showTab: Boolean,
  )
  @Immutable data class PriorityRow(val item: TerminalItem, val place: RowPlace)
  @Immutable data class PrioritySection(val bucket: PriorityBucket, val rows: List<PriorityRow>)
  // MachineSection gains `val priority: List<PrioritySection> = emptyList()`; in Priority mode `spaces` is empty,
  // in Grouped mode `priority` is empty; `items` covers both.
  internal data class ListControls(val filter: TerminalFilter = All, val sort: TerminalSort = TerminalSort.Priority, val showFinished: Boolean = false)
  // TerminalsUiState: `attentionFirst` → `sort: TerminalSort`. TerminalsViewModel: `setSort(TerminalSort)` replaces `setAttentionFirst`.
  ```
  `TerminalItem.urgency` keeps its slot but is computed with `priorityRank` (finished -2, no status -1).

- [ ] **Step 1: Write the failing tests.**
  - core:model: `` `priority rank matches the desktop` `` (the five values above), and `` `space topState prefers done over working` ``.
  - `TerminalsLogicTest`: `` `priority mode puts rows into desktop sections in order` `` (one terminal per state plus one unknown and one finished with showFinished → buckets `[NeedsYou, Done, Working, Idle, NoStatus, Finished]`). `` `priority rows keep core order inside a section` ``. `` `priority row place names space and tab, tab only when the space has several` ``. `` `empty priority sections are hidden` ``. `` `grouped mode orders spaces, tabs and rows by desktop rank, stable` `` (a Done space comes before a Working space). Review Focus 5: `` `priority mode with Working filter and finished hidden` `` → only the Working section, and the host `total` stays the unfiltered count.
  - `TerminalsViewModelTest`: `` `sort defaults to priority and follows the stored preference` ``, `` `setSort persists the choice` `` (through `FakePreferencesRepository`).
  - `TerminalsComposeTest`: `` `sort menu switches between Priority and Grouped by space` ``, `` `unnamed tab shows its number` `` (label `2`, not `Tab 2`).
- [ ] **Step 2: Run them and see them fail.** `cd android && ./gradlew :core:model:test :feature:terminals:impl:testDebugUnitTest`.
- [ ] **Step 3: Implement** in this order: rank and `topState`; preference plumbing; `buildSections` producing either `priority` or `spaces` (the place comes from `Workspace.locate`); the ViewModel combines the stored sort into `controls` (the VM now also injects `PreferencesRepository`); the screen replaces the "Attention first" checkbox item with a two-option sort menu (radio items "Priority" and "Grouped by space") and renders Priority sections as a header (title, count) followed by rows whose subtitle is the place (`%1$s · %2$s`, or the space alone). Section dot colours reuse the existing status colours. Change `workspace_tab_fallback` to `%1$d` in both modules.
- [ ] **Step 4: Run.** `./gradlew :core:model:test :core:data:testDebugUnitTest :core:datastore:testDebugUnitTest :feature:terminals:impl:testDebugUnitTest :feature:terminal:impl:testDebugUnitTest` passes (skip modules without tests).
- [ ] **Step 5: Screenshots.** Add `TerminalsPriorityScreenshot` (phone, phone dark) and keep the existing grouped screenshots by setting `sort = TerminalSort.Grouped` in their state. Regenerate only after inspecting each changed PNG: `./gradlew :feature:terminals:impl:updateDebugScreenshotTest :feature:terminal:impl:updateDebugScreenshotTest`, then `validateDebugScreenshotTest`.
- [ ] **Step 6: Commit** `feat(android): sort terminals by priority like the desktop, show tab numbers`.

---

### Task 5a: Agent catalog and matching (pure)

**Files:**
- Create: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/AgentCatalog.kt`
- Modify: `android/THIRD_PARTY_NOTICES.md` (Herdr.app catalog, Apache-2.0)
- Test: `android/core/model/src/test/kotlin/com/mtajchert/flocket/core/model/AgentCatalogTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class AgentKind(
      val id: String, val name: String, val run: List<String>, val match: List<String>,
      val tint: Boolean, val tileArgb: Long, val colorArgb: Long,
  )
  object AgentCatalog {
      val agents: List<AgentKind>          // the 20 agent entries, desktop order
      val interpreters: Set<String>
      fun of(record: TerminalRecord): AgentKind?   // null = generic terminal tile
  }
  ```
- Data source: the agent entries of the desktop's `"programs"` table, from `claude` through `ollama`: claude, codex, pi, opencode, cursor, copilot, amp, grok, antigravity, kimi, kiro, hermes, mastracode, qoder, muse, crush, command-code, prime-agent, gemini, ollama. Also its `"interpreters"` array. Extract them with
  `strings -n 6 /Applications/Herdr.app/Contents/MacOS/Herdr | grep -A140 '"programs": {' | head -25`
  and `strings -n 6 /Applications/Herdr.app/Contents/MacOS/Herdr | grep -m1 '"interpreters":'`. Record Herdr.app version 0.1.11 in the KDoc. Leave out the editor and tool entries (`vim`, `git`, …) and the generic `agent`, `shell` and `program` entries.

- [ ] **Step 1: Write the failing tests:** `` `reported app matches by match list` `` (`status.app="claude-code"` → claude). `` `reported app matches by id` `` (`"codex"`). `` `argv basename matches run` `` (`argv=["/opt/bin/cursor-agent"]` → cursor). `` `interpreters are skipped in argv` `` (`["node","/x/bin/codex"]` → codex). `` `foreground name is never used` `` (`argv=["sh"]`, foreground name `"claude"` → null). Review Focus 4: `` `unknown reported app under node is generic` `` (`status.app="mytool"`, argv `["node","/x/mytool.js"]` → null). `` `catalog has 20 agents with unique ids` ``.
- [ ] **Step 2: Run them and see them fail.** `cd android && ./gradlew :core:model:test --tests '*AgentCatalogTest*'`.
- [ ] **Step 3: Implement** with a hand-written Kotlin list (no JSON parsing at runtime). The notice entry names the source and its licence.
- [ ] **Step 4: Run.** `./gradlew :core:model:test` passes.
- [ ] **Step 5: Commit** `feat(android): agent catalog adapted from the desktop app`.

---

### Task 5b: Agent icons in the list and the tab strip

**Files:**
- Create: `android/core/ui/src/main/res/drawable/ic_agent_<id>.xml` (one per catalog agent) and `ic_agent_terminal.xml`
- Create: `android/core/ui/src/main/kotlin/com/mtajchert/flocket/core/ui/component/AgentIcon.kt`
- Modify: `android/THIRD_PARTY_NOTICES.md` (LobeHub icons, MIT, pinned version)
- Modify: `feature/terminals/impl/.../TerminalsModels.kt` (`TerminalItem.agentId: String?`), `TerminalsLogic.kt` (`toItem` sets it from `AgentCatalog.of`), `TerminalRow.kt` (tile at row start)
- Modify: `feature/terminal/impl/.../TerminalTabStripModel.kt` (`StripTab.agentId: String?`, from the tab's target pane) and `TerminalTabStrip.kt`
- Test: `core/ui` compose test for `AgentIcon`, `TerminalsLogicTest`, `TerminalTabStripModelTest`, and the screenshot tests of both features

**Interfaces:**
- Consumes: `AgentCatalog`, `AgentKind` (Task 5a).
- Produces: `@Composable fun AgentIcon(agentId: String?, modifier: Modifier = Modifier, size: Dp = 28.dp)`. A rounded tile (corner 25%) in `tileArgb`. `tint` icons are drawn in `colorArgb`, the others untinted. `null` or an unknown id → `ic_agent_terminal` on the same tile colour as the catalog (`#1c1d21`). The content description is the agent name, or "Terminal".

- [ ] **Step 1: Check the converter before planning around it.** Take the SVGs from the npm package `@lobehub/icons-static-svg` at a pinned version (`npm view @lobehub/icons-static-svg version`, 5 s timeout). Convert with `npx -y svg2vectordrawable` (check that it runs on one file first). If an icon fails to convert, or uses gradients a VectorDrawable can't show, use the generic tile for that agent and note it in the commit.
- [ ] **Step 2: Write the failing tests:** `` `row item carries the matched agent id` `` (TerminalsLogicTest). `` `strip tab carries its target pane's agent` `` (TerminalTabStripModelTest). `` `AgentIcon describes the agent or Terminal` `` (core:ui compose test).
- [ ] **Step 3: Run them and see them fail.** `cd android && ./gradlew :feature:terminals:impl:testDebugUnitTest :feature:terminal:impl:testDebugUnitTest :core:ui:testDebugUnitTest`.
- [ ] **Step 4: Implement:** drawables, `AgentIcon`, row and strip placement (row: 28 dp tile before the agent label; strip: 16 dp tile before the tab label).
- [ ] **Step 5: Run the tests, then the screenshots** (inspect, update, validate) for `feature:terminals:impl` and `feature:terminal:impl`.
- [ ] **Step 6: Commit** `feat(android): show agent icons in the terminal list and tab strip`.

---

### Task 4: Rename UI (list and tab strip)

**Files:**
- Modify: `feature/terminals/impl/.../TerminalsModels.kt` (`SpaceSection.layoutRef`, `SpaceSection.givenName`, `TabSection.layoutRef`, `RowPlace.spaceRef`, `RowPlace.tabRef`, `RowPlace.spaceGivenName`; new `TerminalDialog.Rename`)
- Modify: `TerminalsLogic.kt` (copy refs from `WorkspaceSpace`/`WorkspaceTab`), `TerminalsViewModel.kt`, `TerminalsScreen.kt`, `TerminalRow.kt` (overflow items), `TerminalDialogs.kt` (rename dialog), `res/values/strings.xml`
- Modify: `feature/terminal/impl/.../TerminalTabStripModel.kt` (`StripTab.layoutRef`), `TerminalTabStrip.kt` (long-press), `TerminalViewModel.kt` (rename action and events), strings
- Consider: move the rename dialog composable to `core:ui/component/RenameDialog.kt` so both features share it (core may not depend on features)
- Test: `TerminalsViewModelTest.kt`, `TerminalsComposeTest.kt`, `TerminalViewModelTest.kt`, `TerminalScreenTest.kt`, plus screenshots of the dialog

**Interfaces:**
- Consumes: `LayoutRef`, `WorkspaceSpace.layoutRef/givenName`, `WorkspaceTab.layoutRef` (Task 1); `CoreSession.renameInLayout` (Task 2); `PriorityRow`, `RowPlace`, `SpaceSection`, `TabSection` (Task 3).
- Produces:
  ```kotlin
  data class Rename(val hostId: HostId, val ref: LayoutRef, val current: String, val placeholder: String, val text: String = current) : TerminalDialog
  // TerminalsViewModel
  fun requestRename(hostId: HostId, ref: LayoutRef, current: String, placeholder: String)
  fun updateRename(text: String); fun confirmRename()
  ```
  Exact copy (strings.xml): `rename_tab_title` "Rename tab", `rename_space_title` "Rename space", `rename_save` "Save", `rename_hint_auto` "Leave empty to use “%1$s”", `rename_error_conflict` "The desktop changed this layout at the same time. Try again.", `rename_error_gone_tab` "This tab was closed on the desktop.", `rename_error_gone_space` "This space was closed on the desktop.", `rename_error_unreachable` "Can't rename now: %1$s isn't reachable.", `rename_outcome_unknown` "Connection dropped while renaming; showing the name the desktop has now.", `menu_rename_tab` "Rename tab…", `menu_rename_space` "Rename space…".

- [ ] **Step 1: Write the failing tests:**
  - VM: `` `rename calls renameInLayout with the ref and trimmed text` ``. `` `rename errors map to the exact messages` `` (`Rpc(CONFLICT)`, `Rpc(NOT_FOUND)` for a tab and for a space, `Rpc(UNAVAILABLE)` with `ref.machine = "rohan"` → "rohan", `ConnectionLost` → the host label, `OutcomeUnknown` → `rename_outcome_unknown`). `` `rename is not offered while the host is stale` ``.
  - Compose: `` `long-press on a desktop space header opens rename prefilled with its given name` ``. `` `no rename on a folder space or the implicit tab` ``. `` `priority row overflow has Rename tab and Rename space only when refs exist` ``. `` `strip tab long-press opens rename` `` (TerminalScreenTest).
- [ ] **Step 2: Run them and see them fail.** `cd android && ./gradlew :feature:terminals:impl:testDebugUnitTest :feature:terminal:impl:testDebugUnitTest`.
- [ ] **Step 3: Implement.** The dialog is a text field (single line, IME action Done = Save) with the placeholder as its supporting text. Show a rename action only when its ref is non-null and the host is `Online`. Errors go to the existing snackbar event path (`TerminalsEvent.Failure`/`Info`/`OutcomeUnknown`, and the equivalent in the terminal feature).
- [ ] **Step 4: Run the tests, then the screenshots** (dialog, phone and dark): inspect, update, validate.
- [ ] **Step 5: Commit** `feat(android): rename spaces and tabs from the list and the tab strip`.

---

### Task 6: Docs and full check

**Files:**
- Modify: `android/README.md` or the user-facing feature list, if it describes the list or sorting (`grep -n -i "attention first\|sort" android/README.md README.md`)
- Modify: `docs/ROADMAP.md`, if it lists any of these items
- Modify: `docs/design/2026-11-01-workspace-rename-sort-icons.md` status → "Implemented" with the merge commits

- [ ] **Step 1: Update the docs** listed above. Committed docs never link to ignored files.
- [ ] **Step 2: Run the full check.** `cd android && scripts/check.sh full` passes.
- [ ] **Step 3: Whole-branch review** (Opus) of the combined diff since `cf82da9`, focused on the Review Focus list.
- [ ] **Step 4: Commit** `docs: workspace rename, priority sort and agent icons`.
- [ ] **Step 5: Hand over for the manual desktop check** (spec Verification, last bullet). Do not release; the user asked for no release yet.
