# DataBackup plan worktree

This directory is a git worktree of the DataBackup repository, checked out on the `plan` branch. It holds
`PLAN.md`, the step-by-step plan for finishing the project.

- **Code** lives in the main worktree at `/Users/siggi/Desktop/java/DataBackup` on the `main` branch.
- **The plan** lives here, at `/Users/siggi/Desktop/java/DataBackup/plan`, on the `plan` branch.
- The main worktree ignores `/plan`, `/CLAUDE.md` and `/PLAN.md` through `.git/info/exclude`, so plan files never
  end up in `main` commits.

## Working through a plan step

1. Read `PLAN.md` from the top: the **Decisions** section and any earlier `Done:` notes give context that later
   steps rely on.
2. Do the work for the first unchecked `- [ ]` step only, in the main worktree. Stay within that step's scope; if you
   find something else that needs doing, add it to `PLAN.md` as a new unchecked step in the right phase instead of
   doing it.
3. Before committing, make sure `mvn -o -q test` passes from the main worktree root (drop `-o` if a dependency isn't
   cached yet). Do not mark a step done while the build or tests fail.

## When a plan step is complete

1. **Commit `main`.** In the main worktree, commit the step's code changes to `main` with a message describing the
   change. Don't commit files that `.gitignore` or `.git/info/exclude` exclude.
2. **Update the plan with the commit hash.** In this worktree, change the step's `- [ ]` to `- [x]` and add a nested
   note directly under the step's title line, in this form:
   ```
     - Done: `<short hash>` (<YYYY-MM-DD>). <one or two sentences on what was done, anything that differed from the
       plan, and anything later steps need to know>
   ```
   If the step needed more than one commit, list every hash. Then commit `PLAN.md` on the `plan` branch.
3. **Push both branches:**
   ```
   git -C /Users/siggi/Desktop/java/DataBackup push origin main
   git -C /Users/siggi/Desktop/java/DataBackup/plan push -u origin plan
   ```
   (`-u` is only needed the first time, while `plan` has no upstream.)

If a push fails, report the error and stop; don't force-push.

## Manual steps and the "awaiting verification" marker

Steps marked **(manual)** need hardware, an OS, or privileges an agent doesn't have, such as a Linux machine with
btrfs, a Windows machine, or root. Don't fake them.

- Do whatever preparation can be done: build the jars, write sample config, and commit any helper scripts or docs to
  `main` as usual.
- Write the exact commands for the user to run, and what they should report back, in
  `docs/manual-checks/<platform>.md` on `main`.
- Then mark the step **implemented, awaiting verification** by changing `- [ ]` to `- [~]`, and add a note:
  ```
    - Awaiting verification: `<short hash>` (<YYYY-MM-DD>). User must follow docs/manual-checks/<platform>.md and
      report results.
  ```
  Commit and push as for a completed step. The `run-plan` workflow counts `- [~]` as done for this run, so it moves
  on instead of stopping.
- Only the user moves a step from `- [~]` to `- [x]`. If they report problems, add new `- [ ]` fix steps directly
  under the manual step.

## Running this plan with the `run-plan` workflow

The workflow lives in `~/.claude/workflows/run-plan.js`. Run it from the main worktree and pass the plan path, because
its default is `PLAN.md` in the current directory: `args: {"plan": "plan/PLAN.md"}`. Optionally add `maxSteps` (the
default is 30).

The workflow shows each worker only the step's `- [ ]` title line. The step's details are in the indented
sub-bullets under it in `PLAN.md`, so read them.

## Project conventions

- Java 17, Maven multi-module: `common` (format, scanner, snapshots, content store), `serverlib` (auth,
  compression), `server`, `client`.
- Local dependencies (`SiggiTools`, `SiggiHTTP`, `Stat`, `ReflectionFreedom`) come from `~/.m2`, so build with
  `mvn -o` when possible.
- Don't add third-party dependencies unless a step says to. Gson is already available for JSON.
- `common/src/main/java/io/siggi/databackup/main/` is the user's personal scratch area and is excluded from git.
  Don't put real code there.
