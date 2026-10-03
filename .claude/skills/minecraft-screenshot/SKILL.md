---
name: minecraft-screenshot
description: Take an in-game screenshot from the running dev client (./gradlew runClient) and show it to the user in chat. Use when the user asks to see a block, item, model, texture or machine in game, or to show your work visually.
---

# Minecraft screenshot

`scripts/mc-shot.sh` controls the dev client that is already running. It sends chat commands as keystrokes, hides the HUD, presses F2, then moves the player back to where they were. It also refocuses whatever app was in front before. It prints every chat line from the run and ends with `SCREENSHOT: <absolute path>`.

## Requirements

- macOS. The dev client must be running with a world open and cheats on. The user's test world is `run/saves/flat overworld`, a superflat world: grass at y=-1, players stand at y=0, the Overworld spawn is at 0 0 0, and it has villages.
- The app running the agent needs macOS Accessibility permission so it can send keystrokes.
- The active keyboard input source must be a Latin layout (ABC/US), because commands are typed as keystrokes. Minecraft ignores Cmd+V sent by System Events, so pasting isn't an option.
- If the client isn't running, ask the user to start it with `./gradlew runClient` and open a world. Don't start a second client: it shares `run/`, so the world lock would conflict.
- The script takes over the game window for about 10–20 seconds. Tell the user before you run it.

## Usage

```bash
.claude/skills/minecraft-screenshot/scripts/mc-shot.sh [--wait SECONDS] [--keep-hud] [--no-restore] COMMAND... 
```

Each argument is one chat command, starting with `/`. `@wait N` pauses between commands; use it after a `/tp` so chunks can load before `/fill` or `/setblock`. `--wait` sets how long to wait before the screenshot (default 2s).

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

## Showing the result

1. Read the `--- chat ---` output. Errors such as `Unknown block type` or `That position is not loaded` mean the scene is wrong. Fix the commands and run again.
2. Open the screenshot with the Read tool and check it shows what you meant to show.
3. Show it to the user with an absolute-path Markdown image: `![Quarry in game](/abs/path/run/screenshots/2026-....png)`.

## Rules

- Never run world-wide destructive commands. In particular, don't run `/kill @e` without a tight `distance=..N` around the stage. An unfiltered kill once wiped out a village.
- Only build at the stage area. Don't edit terrain at spawn or anywhere else, unless the user says to.
- Don't guess the ground height. Check the world's generator settings, or use the flat world values above.
- Tell the user about any lasting change to the world: blocks placed, time or weather changed.
- F1 is a toggle. If the user had the HUD hidden already, pass `--keep-hud`.
