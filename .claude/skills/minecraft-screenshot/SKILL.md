---
name: minecraft-screenshot
description: Take an in-game screenshot from the dev client (./gradlew runClient) and show it to the user in chat, reloading or relaunching the game first if the code changed. Use when the user asks to see a block, item, model, texture or machine in game, or to show your work visually.
---

# Minecraft screenshot

Two scripts in `scripts/`:

- `mc-refresh.sh` brings the dev client up to date with the working tree. Depending on what changed, it reloads resources in place or rebuilds and relaunches the game. If the client isn't running, it starts it.
- `mc-shot.sh` drives the running client. It types chat commands, hides the HUD, presses F2, then moves the player back to where they were and refocuses the app that was in front before. It prints every chat line from the run and ends with `SCREENSHOT: <absolute path>`.

## Requirements

- macOS. The user's test world is `run/saves/flat overworld`, a superflat world with cheats on: grass at y=-1, players stand at y=0, the Overworld spawn is at 0 0 0, and it has villages.
- The app running the agent needs macOS Accessibility permission so it can send keystrokes.
- The active keyboard input source must be a Latin layout (ABC/US), because commands are typed as keystrokes. Minecraft ignores Cmd+V sent by System Events, so pasting isn't an option.
- Never start a client with plain `./gradlew runClient` while one is running. Both share `run/`, so the world lock would conflict. Use `mc-refresh.sh`, which quits the old client first.
- The scripts take over the game window: about 15 seconds for a screenshot and about 40 seconds for a relaunch. Tell the user before you run them.

## Step 1: get the game up to date

The client only shows the code it was started with, so a screenshot taken after you change code shows the old version. Before every screenshot, check what the client is missing:

```bash
.claude/skills/minecraft-screenshot/scripts/mc-refresh.sh --check
```

It lists files changed since the client started (or since the last refresh) and what a refresh would do. If it says `Client is up to date.`, go straight to step 2. Otherwise run it without `--check`:

```bash
.claude/skills/minecraft-screenshot/scripts/mc-refresh.sh
```

| What changed | What the refresh does | Cost |
|---|---|---|
| Only `src/main/resources/assets/` (textures, models, blockstates, lang) | `processResources`, then F3+T | a few seconds; the world stays open |
| `src/main/resources/data/` (loot tables, recipes, tags) | `processResources`, then `/reload` | a few seconds; the world stays open |
| Java code, `fabric.mod.json`, mixin or access widener files, Gradle files | builds, quits the client with Cmd+Q (this saves the world), relaunches it straight into the world | about 40 seconds |
| Client not running | launches it straight into the most recently played world (`MC_WORLD=<save folder>` to pick another) | about 30 seconds |

When to be careful:

- **The refresh takes the whole working tree, not just your changes.** If another agent or the user is editing the repo, their half-finished work goes into the relaunch and can crash the game. If `--check` lists files you didn't touch, ask the user before refreshing.
- **Relaunch only once your change is complete:** it compiles and every file it needs, such as a class named in `fabric.mod.json`, exists. If the build fails, the script stops and the old client keeps running. Fix the build, then refresh.
- **If the relaunched client crashes,** the script prints the cause and the crash report path and says the client is NOT running. Fix the cause and run the refresh again, which starts it. Tell the user the game is down until then.
- **Don't relaunch just to be safe.** Asset-only and data-only changes never need it; the reload is much faster and keeps the user's session.

## Step 2: take the screenshot

```bash
.claude/skills/minecraft-screenshot/scripts/mc-shot.sh [--wait SECONDS] [--keep-hud] [--no-restore] COMMAND...
```

Each argument is one chat command, starting with `/`. `@wait N` pauses between commands; use it after a `/tp` so chunks can load before `/fill` or `/setblock`. `--wait` sets how long to wait before the screenshot (default 2s). If a command gets no chat feedback, the script presses Enter again once, then prints a warning.

Standard scene: stand the block on grass at the stage area x=1000, z=1000, well away from the user's builds near spawn. Clear a small space first so tall grass and flowers don't block the view:

```bash
.claude/skills/minecraft-screenshot/scripts/mc-shot.sh \
  "/tp @s 1002.5 0 1002.5 135 22" "@wait 3" \
  "/time set noon" "/weather clear" \
  "/fill 997 0 997 1004 4 1004 minecraft:air" \
  "/fill 997 -1 997 1004 -1 1004 minecraft:grass_block" \
  "/setblock 1000 0 1000 create_tem_quarry:quarry"
```

Camera angles: Minecraft yaw 0 faces +Z (south), 90 faces -X, 180 faces -Z, -90 faces +X; positive pitch looks down. Standing 2 blocks diagonal from the block at yaw 135, pitch 22 gives a 3/4 view that shows the top and two sides. Move further away for multiblocks or machines. For a Create contraption, build it with `/setblock`/`/fill` at the stage area and give it a few seconds (`--wait 5`) to start running before the screenshot.

## Step 3: show the result

1. Read the output.
   - Errors such as `Unknown block type` or `That position is not loaded` mean the scene is wrong. Fix the commands and run again.
   - A `WARNING: the client is older than these files` line means you skipped step 1. Refresh, then retake.
2. Open the screenshot with the Read tool and check it shows what you meant to show.
3. Show it to the user with an absolute-path Markdown image: `![Quarry in game](/abs/path/run/screenshots/2026-....png)`.

## Rules

- Never run world-wide destructive commands. In particular, don't run `/kill @e` without a tight `distance=..N` around the stage. An unfiltered kill once wiped out a village.
- Only build at the stage area. Don't edit terrain at spawn or anywhere else, unless the user says to.
- Don't guess the ground height. Check the world's generator settings, or use the flat world values above.
- Tell the user about any lasting change to the world (blocks placed, time or weather changed) and about any relaunch.
- F1 is a toggle. If the user had the HUD hidden already, pass `--keep-hud`.
