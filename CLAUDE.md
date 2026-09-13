# CLAUDE.md

Project guidance for Claude Code lives in **[AGENTS.md](AGENTS.md)** — commands, stack,
architecture, constraints and gotchas, tests, and the agent delegation contract.

`AGENTS.md` is canonical because every agent reads it (Antigravity's `agy` reads
`AGENTS.md` and `GEMINI.md` but *not* this file). Adding project knowledge here instead
would leave other agents working from a stale picture, so put it in `AGENTS.md`.

## Claude-only notes

- Implementation work on issues labelled `ready-for-agent` is normally delegated to
  Antigravity rather than written here — see `.claude/skills/delegate-to-agy/SKILL.md`.
  Claude's role is research, specs, implementation plans, verification, and the PR.
- Project agent rules and skills shared with other tools live in `.agents/`; Claude's own
  skills live in `.claude/skills/`.
