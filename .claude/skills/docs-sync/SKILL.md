---
name: docs-sync
description: Bring the docs site (site/, the Tour of Threebolt) in line with a language change — syntax, semantics, std APIs or the CLI. Use after any user-visible language change lands, when the spec changelog gains an entry, or when asked to sync, refresh or audit the docs/tour/site.
---

# Docs sync

The site explains the language; the spec (`docs/spec/threebolt-1.0.md`) defines it. This skill
makes the site agree with the spec and the implementation after a change. Never change the spec or
the language here — if the site reveals a spec problem, stop and tell the user.

## 1. Work out what changed

Use the argument if one was given (a feature, commit range or spec section). Otherwise:

- `git diff main...HEAD --stat` and the diff of `docs/spec/threebolt-1.0.md`, especially new
  `## Changelog` entries — each entry is a checklist item.
- Changes to the grammar, `src/main/resources/std/`, or `Main.java` (CLI) since `main`.

List the user-visible changes in one line each before editing anything.

## 2. Find what the site says about them

For each change, grep `site/src/content/docs/` for the keyword, operator, std name or CLI command,
and check `tests/conformance/tour/` programs that use it. Note pages that are now **wrong**
(describe old behaviour), **incomplete** (a beginner-path feature with no mention), or fine.

Beginner-path test: would someone writing their first few hundred lines of Threebolt meet this?
If yes, it belongs in the tour (a sentence or a short section in the chapter that covers that
area). If no, leave it to the spec.

## 3. Update

- **Prose:** fix the page. Keep the tour's voice: short sections, show-then-explain, call out where
  Rust intuition misleads, link spec sections instead of restating rules.
- **Programs:** a chapter's code block and output are verbatim copies of
  `tests/conformance/tour/<name>.3b` and `.out` (`modules/` holds chapter 8's multi-file case).
  Edit the `.3b` file first, run it with `build/install/bolt/bin/bolt run <file>` (after
  `./gradlew installDist`), write the `.out` from real output, then paste both back into the page
  unchanged. Never hand-write output.
- **New chapter** (rare — a whole new area): `site/src/content/docs/tour/NN-topic.md` with
  `sidebar.order`, plus its program; renumber nothing, the sidebar sorts by `order`.

## 4. Verify

```sh
./gradlew test --tests '*TourSyncTest*' --tests '*ConformanceTest*'   # pages match programs; programs run
cd site && npm run build                                              # site builds, no new warnings
```

`TourSyncTest` fails with the page and line of any block that matches no program, and lists
programs no page shows.

## 5. Report

One line per page touched (what changed and why), plus anything deliberately left to the spec.
Commit as `Tour: <what>` if the user wants a commit.
