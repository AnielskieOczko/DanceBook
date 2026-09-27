# Running Claude Code and agy autonomously in a Docker container

**Status:** exploration only, nothing built. Written 2026-09-27 so the idea can be picked
up later.

## Why

Approving commands one by one is slow. Most prompts come from
`blockReadsOutsideWorkingDirectories`, which sends every Bash command the parser can't
analyse to the user. The idea is to run both agents in a container with full autonomy:
`claude --dangerously-skip-permissions`, and agy unrestricted. The container, not the
approvals, then becomes the safety boundary.

Today the boundary is the disposable agy clone. `command(*)` in agy's settings still lets
it write anywhere on the Mac. The earlier sandbox decision named a container as the option
that would give both isolation and the ability to run the build.

## Short answer

**It works, with one real problem (a Docker engine for Testcontainers) and one unknown
(where agy stores its login on Linux).**

What the container solves:
- A runaway command can't reach the Mac filesystem, keychain, SSH keys, other projects or
  IntelliJ config. The agent sees only what is mounted.
- `--dangerously-skip-permissions` becomes reasonable. This is the setup Anthropic's
  reference devcontainer is built for.
- agy can run fully unrestricted, which is safer than today's `command(*)` on the host.

What it does **not** solve:
- **Credentials inside can be stolen.** The Claude login, agy's Google login and the `gh`
  token all live in the container. A prompt-injected agent with network access could send
  them out. Mitigations: an egress allowlist firewall (as in Anthropic's
  `init-firewall.sh`), and a fine-grained GitHub PAT limited to this repo with no admin
  rights.
- **Mounted files are writable.** If the main repo is mounted, an agent can plant
  `.git/hooks` or change `build.gradle.kts`, and that code runs on the Mac the next time
  IntelliJ or git runs. Mount **only the agy clones and a separate container checkout**,
  never the main repo. Review a branch before running it on the host, as happens today.
- **Mounting the host Docker socket cancels the whole idea.** Anyone with the socket can
  run `docker run -v /:/host`.

## Does the current workflow work inside?

| Piece | In a container | Work |
|---|---|---|
| Claude Code CLI | ✅ Official devcontainer support. Log in once and keep `~/.claude` in a volume. | Low |
| Skills, AGENTS.md, memory | ✅ if the repo is mounted at the **same path** (`/Volumes/my-data/Developer/Projects/...`). The skills use literal paths, and Claude's memory is keyed by project path. | Low |
| `gh` issues and PRs | ✅ with a repo-scoped PAT | Low |
| **agy** | ✅ Official Linux build: `curl -fsSL https://antigravity.google/cli/install.sh \| bash` installs the aarch64 binary. Headless login prints a URL; you sign in on the Mac and paste the code back. ⚠️ agy stores its login in the **OS keyring** (Secret Service over dbus on Linux), which a bare container lacks. Check whether the login survives a restart, or whether the image needs `gnome-keyring` and dbus. Don't use `GEMINI_API_KEY`: it bills per token and bypasses the Pro subscription. | Probe |
| `./gradlew build`, unit tests | ✅ JDK 21 in the image, Gradle cache in a volume | Low |
| **Testcontainers tests + `dancebook-db`** | ❌ without a Docker engine, ✅ with a Docker-in-Docker sidecar (see below) | **Main problem** |
| run-dancebook visual checks (Playwright) | ✅ `driver.mjs` already falls back from `channel: 'chrome'` to bundled Chromium. The image needs only `npx playwright install --with-deps chromium`, and headless screenshots work unchanged. Google Chrome for Linux arm64 has also been in Google's apt repo since mid-2026, if real Chrome is wanted. | No code change |
| Claude-in-Chrome / the desktop app's browser | ❌ They drive the Chrome on the Mac, which the container can't reach. The Playwright driver covers the same checks. | Accept |
| agy Stop gate, `agy-status.sh` | ✅ They read files. agy's brain path moves into the container's `~/.gemini`. | Low |
| Approval prompts from `blockReadsOutsideWorkingDirectories` | ✅ Gone inside the container. The setting stays on for host sessions. | — |

## Testcontainers and the database

**Why it matters.** More than 10 test classes (for example `WebRouteSmokeTest` and
`TrainingEventSpecificationTest`) each start their own
`PostgreSQLContainer("postgres:16-alpine")` with `@ServiceConnection`. Without a Docker
engine, every one of them fails:
- `./gradlew build` is always red;
- agy's Stop gate never sees a green `:test`, so agy spends its 3 build rounds and quits;
- `verify-agy-work`'s own build fails too.

Running those tests on the Mac instead would mean running agy's unreviewed Gradle code
outside the container, which undoes the isolation.

**Options:**

| Option | Tests | run-dancebook (the app) | Verdict |
|---|---|---|---|
| A. Plain Postgres sidecar, no Testcontainers | Needs a refactor: a shared base class with an env-switched datasource, and per-class isolation for 10+ classes. Tests then behave differently on the host and in the container. | Works | Not worth it |
| B. The Mac's `dancebook-db` via `host.docker.internal:5432` | Same refactor as A | Works, but copying a DB has to use `createdb -T` over the network instead of `docker exec`, and agents see your real dev data | App only |
| **C. Docker-in-Docker sidecar** (`docker:dind`, privileged, on a compose network) | **No changes.** Set `DOCKER_HOST=tcp://dind:2375` and `TESTCONTAINERS_HOST_OVERRIDE=dind`, since mapped ports appear on the dind host. | `dancebook-db` also runs in dind (or as a sidecar), seeded once from a `pg_dump` of the dev DB | **Recommended** |

The dind engine lives inside Docker Desktop's Linux VM and sees none of the Mac's files.
Testcontainers Cloud is another option, but it is an external service.

**Resources.** The Docker Desktop VM has 5 CPUs and 8 GB. Gradle, Testcontainers Postgres,
dind, the app and Chromium together will be tight, so raise it to 12 GB or more.

## Cheaper alternatives to try first

- **Claude Code's built-in sandbox (`/sandbox`).** It isolates the filesystem and network
  at the OS level on macOS, and can run sandboxed Bash without prompts. It may remove most
  prompts without Docker, although Gradle and Testcontainers may hit the same limits as
  agy's `--sandbox`.
- **Docker Desktop's agent sandboxes** (`docker sandbox run claude`), if the installed
  Docker version has them.

## Proposed spike (time-boxed, go/no-go at each gate)

1. **Gate A: agy login survives in a container.** In a throwaway `debian` container,
   install agy, do the paste-code login, restart with `~/.gemini` on a volume, and run
   `agy -p='say hi'`. If the keyring blocks it, add `gnome-keyring` and
   `dbus-run-session`. If it still fails, stop: Claude alone in a container works, but
   delegation doesn't.
2. **Gate B: `/sandbox` on the host.** If it removes the prompts and runs `./gradlew
   build`, that may be enough.
3. **Build `.devcontainer/`** on a branch, based on Anthropic's reference devcontainer
   (Dockerfile, `init-firewall.sh`, `devcontainer.json`), plus:
   - JDK 21, Node, `gh`, Playwright Chromium, agy (Linux);
   - a compose file with the agent container, a `docker:dind` sidecar and Postgres;
   - bind mounts at identical paths for a separate checkout
     (`/Volumes/my-data/Developer/Projects/DanceBook-box`) and the agy clone parent;
   - named volumes for `~/.claude`, `~/.gemini` and `~/.gradle`;
   - a firewall allowlist: Anthropic API, Google/Gemini endpoints, GitHub, Maven Central,
     Gradle plugin portal, npm, Docker Hub.
4. **Run the real workflow end to end** on one small `ready-for-agent` issue:
   delegate-to-agy, agy's build green (Testcontainers via dind), verify-agy-work, a
   run-dancebook screenshot, then the PR.
5. **Adapt the skills only where needed**, for example the `docker exec dancebook-db`
   target and `DOCKER_HOST`. Update AGENTS.md afterwards.

## Verification

- `./gradlew build` in the container is green, Testcontainers tests included.
- From inside, reading the Mac's `/Users` or `~/.ssh` fails, and `curl example.com` is
  blocked by the firewall.
- One delegated issue reaches a PR with no approval prompts.
- On the host, `docker ps` shows no agent-created containers outside dind.

## Sources

- [Antigravity CLI: Installation & Auth](https://antigravity.google/docs/cli/install/)
- [Arm install guide for the Antigravity CLI](https://learn.arm.com/install-guides/antigravity/)
- [Chrome arrives on Arm64 Linux (OMG! Ubuntu, July 2026)](https://www.omgubuntu.co.uk/2026/07/chrome-arm64-linux-available)
