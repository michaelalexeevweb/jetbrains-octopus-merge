# Octopus Merge

JetBrains IDE plugin: merge several Git branches into one with a single merge commit
(an octopus merge), with conflict preview and conflict resolution in the IDE merge dialog.

## Install

1. Download `JetbrainsOctopusMerge-<version>.zip` from the [latest release](https://github.com/michaelalexeevweb/jetbrains-octopus-merge/releases/latest).
2. Settings → Plugins → ⚙ → Install Plugin from Disk… → choose the zip → restart the IDE.

Requires an IntelliJ-based IDE 2026.3 or newer (build 263+) with the bundled Git plugin.

## Where

- **Git → Octopus Merge…** (right after Merge…)
- Git tool window: context menu of selected branches or commits. The selection is pre-checked.

## Dialog

- **Merge into**: target branch. **Branches**: checkbox list with search (local and remote).
- **Merge order**: checked branches in order, reorder with ↑/↓. Each branch shows a preview:
  commits it brings, conflicts expected at its position (computed with `git merge-tree`, nothing is touched),
  or "already merged".
- **Resolve in advance**: double-click a branch with expected conflicts (or press Enter). The IDE merge window
  opens on in-memory copies; the branch turns ✓ "resolved in advance" and the merge applies your result without
  stopping. A resolution is tied to the exact conflict (same file, same three versions): after reordering or a branch
  update it is not used and you are asked again.
- **Message**: follows the selection until edited.
- **Options** (remembered):

| Option | Effect |
|---|---|
| On conflict | resolve in the merge dialog / keep target (`-X ours`) / take branch (`-X theirs`) |
| Stash local changes | stash before, restore after; on failure changes come back too |
| `--no-commit` | stage the result with `MERGE_HEAD` listing every branch; commit from the Commit tool window |
| `--log` | list merged commits in the message |
| `--signoff`, `-S` | Signed-off-by trailer, GPG signature |
| `--no-verify` | skip `pre-merge-commit` / `commit-msg` hooks |
| `--allow-unrelated-histories` | merge branches with no common history |
| Delete merged branches | delete merged local branches after the commit |

## How it merges

1. Branches already contained in the target or in another selected branch are skipped.
2. Native `git merge` with all branches (octopus strategy). Done if nothing conflicts.
3. Otherwise branches are merged one by one; conflicts resolved in advance are applied, the rest open the IDE merge dialog.
   The step commits are then replaced by one commit (`git commit-tree`) whose parents are the target
   and every branch: the same history an octopus merge gives. Hooks run as for a normal merge.
4. Cancel or failure restores the target, the checked-out branch and stashed changes.

The success notification has **Undo** (reset to the previous commit, recreate deleted branches);
a prepared `--no-commit` merge has **Commit…** and **Abort**.

## Build

Needs JDK 25 (or the JBR bundled with the IDE). The IDE to build against is `localIdePath` in `gradle.properties`.

```bash
./gradlew test          # core tests on real git repositories + IDE integration tests
./gradlew buildPlugin   # build/distributions/JetbrainsOctopusMerge-<version>.zip
./gradlew verifyPlugin  # JetBrains Plugin Verifier against the local IDE
./gradlew runIde        # sandbox IDE with the plugin
```
