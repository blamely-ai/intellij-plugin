# Blamely — Product Detail

## Welcome to Blamely

**You wrote the prompt. But who wrote the code?**

Blamely brings AI contribution tracking directly into your IDE. As AI-assisted coding becomes the norm, Blamely answers the question every developer and every team is starting to ask: **how much of this codebase is actually human?**

Blamely runs quietly in your IDE and analyzes your git history commit by commit. It detects which lines were written by AI tools — GitHub Copilot, Cursor, Codeium, Claude, and others — and which were written by hand. The results appear instantly in a clean, native tool window: **no external services, no sign-up, no telemetry.**

Blamely doesn’t judge. It just shows you the numbers — and lets you decide what they mean.

*Because git blame only tells half the story.*

---

## How It Works

### 1. **Line-level attribution**

- As you type or accept AI suggestions, Blamely attributes each changed line to **AI** or **Human** (character and line counts, percentages).
- Attribution uses a **95% rule**: when most of the characters on a line are from one source, the whole line is attributed to that source.
- **Formatting-only** changes (e.g. Reformat Code) do not change ownership — existing AI/Human blame is preserved.

### 2. **AI detection**

Blamely detects when code comes from:

- **GitHub Copilot** (completion, chat panel, inline)
- **Cursor**
- **Codeium**
- **Tabnine**
- **Supermaven**
- **CodeGPT**
- **Amazon CodeWhisperer**
- **JetBrains AI**

Detection uses suggestion traces (when available), a short “mark next as AI” window after AI actions, and optional stack inspection for chat-panel applies.

### 3. **Where you see it**

| Place | What you see |
|-------|----------------|
| **Status bar** | Live AI / Human chars and lines; click to open the Blamely tool window. |
| **Gutter** | Optional line icons (AI / Human) next to line numbers for uncommitted changes (Settings → Tools → Blamely). |
| **Tool window → Changes** | Per-file AI / Human breakdown for **uncommitted** changes. |
| **Tool window → History** | Commits, authors, AI %, coding time, branch; top AI models and full commit list. |

### 4. **Persistence and git**

- Blame is stored under **`.git/blamely`** and survives IDE restart, branch switch, and stash.
- You can attach a **report** and **line blame** to each commit as **git notes** under `refs/notes/blamely`.
- An optional **pre-push hook** pushes these notes to the remote (cross-platform; does not overwrite existing hooks).

### 5. **Report YAML (report.yml) and formatting**

Blamely produces a **report.yml** per commit. It summarizes AI vs human line counts, models, and metrics in a standard YAML format (aligned with the Blamely open standard and VS Code plugin).

**Where it is written**

- **Tools → Blamely → Generate Report**: writes `.git/blamely/report.yml` for the current branch/commit.
- **On commit**: the commit listener generates a report from the blame snapshot and stores it in the git note for that commit (with the line-level blame snapshot).

**report.yml structure (top-level keys)**

| Key | Description |
|-----|-------------|
| `scope` | Always `"this_commit"` (report covers a single commit). |
| `commitDate` | ISO-8601 timestamp when the report was generated. |
| `detector_version` | Blamely detector version (e.g. `"0.2.0"`). |
| `branch` | Git branch name at report time. |
| `commit_hash` | Full commit SHA. |
| `commit_message` | Commit message (JSON-escaped). |
| `summary` | Totals: `total_files_changed`, `total_lines_added`, `total_lines_deleted`, `total_changes`, `ai_lines_added`, `human_lines_added`, `ai_percentage`, `model_count`. |
| `metrics` | `first_start_coding_time` (ISO-8601 or null), `time_waiting_for_ai_ms`. |
| `agent_info` | `ide` (e.g. `"IntelliJ"`), `models` (list), `interaction_types` (e.g. completion, chat_panel). |
| `files` | List of per-file entries (see below). |

**Per-file entry (under `files`)**

Each item has:

- `path` — Project-relative file path.
- `source` — AI provider (e.g. `"copilot"`, `"multiple"`).
- `model` — Model name or `"unknown"` / `"multiple"`.
- `ai_lines_added`, `human_lines_added`, `lines_deleted` — Line counts.
- `total_changes` — Sum of added and deleted lines.
- `ai_percentage` — String, e.g. `"42.5%"`.
- `prompts` — List of user prompts (JSON strings) that led to AI output for this file.

**Line-level blame (in git notes)**

The git note for a commit can also include a **blame snapshot**: a YAML map from file path to a list of line entries. Each line entry has:

- `lineNumber` (or `newLineNumber`)
- `authorType`: `"AI"` or `"HUMAN"`
- `provider`, `model`, `prompt`, `interactionType` (optional)
- `changeType`: `"ADD"` or `"DELETE"`
- `codingType`: `"TYPING"` or `"BULK_INSERT"`

**Formatting rules**

- Strings that may contain newlines or quotes are JSON-encoded (e.g. `commit_message`, `prompts` entries).
- File paths and other simple strings are quoted and backslash-escaped where needed.
- Numbers and `null` are emitted as YAML literals. Percentages are strings (e.g. `"35.2%"`).

**Example (minimal report.yml)**

```yaml
scope: "this_commit"
commitDate: "2025-03-10T12:00:00Z"
detector_version: "0.2.0"
branch: "main"
commit_hash: "abc123def456"
commit_message: "Add feature X\n"

summary:
  total_files_changed: 2
  total_lines_added: 45
  total_lines_deleted: 3
  total_changes: 48
  ai_lines_added: 30
  human_lines_added: 15
  ai_percentage: "62.5%"
  model_count: 1

metrics:
  first_start_coding_time: "2025-03-10T11:55:00Z"
  time_waiting_for_ai_ms: 1200

agent_info:
  ide: "IntelliJ"
  models:
    - "claude-3"
  interaction_types:
    - completion

files:
  - path: "src/App.kt"
    source: "cursor"
    model: "claude-3"
    ai_lines_added: 20
    human_lines_added: 8
    lines_deleted: 1
    total_changes: 29
    ai_percentage: "69.0%"
    prompts:
      - "Add a login function"
```

### 6. **Coding types and metrics**

- **TYPING** — normal typing; **BULK_INSERT** — paste.
- File add/move is attributed appropriately.
- **Metrics**: first coding time in session, time waiting for AI, interaction types (completion, chat_panel, etc.).

### 7. **Actions (Tools → Blamely)**

- **Generate Report** — Regenerate report and blame state.
- **Show Blame for Current File** — AI / Human breakdown for the active editor.
- **Install / Restore Git Hook** — Install or restore the pre-commit hook.
- **Show Commit Report** — Open report from git notes for the latest commit.
- **Attach Git Note** — Add or overwrite the Blamely note for the current commit (e.g. fix “no note found”).
- **Push Notes to Remote** — Push `refs/notes/blamely` so the remote has notes.

### 8. **Settings**

- **Settings → Tools → Blamely**: Toggle “Show line icons in gutter” (AI / Human next to line numbers).
- **Settings → Editor → General → Gutter Icons**: Show or hide the Blamely gutter entry.

### 9. **Open standard**

Blamely uses the same **.git/blamely** format for cross-IDE compatibility. Learn more at [blamely.ai](https://blamely.ai).

---

## Why developers use Blamely

- Understand your real contribution before a code review.
- See how your AI usage evolves over time.
- Make informed decisions about what to refactor or own.
- Align with team or company policies around AI-generated code.
