# MineColonies Vanilla Tweaks

An addon for [MineColonies](https://minecolonies.com) that redraws its windows as vanilla Minecraft
Java Edition screens, and adds a few tools for running a colony.

> Unofficial addon: not made by or affiliated with the LDT Team, the authors of MineColonies.

## Features

- **Vanilla-style screens** for every MineColonies window a player can reach:
  - citizens (overview, skills, inventory, requests, happiness, family, dialogues), with a "why isn't this citizen
    working?" diagnosis;
  - the town hall (dashboard, actions, permissions, statistics, alliances, colony map, quest log, banner);
  - every hut (home, workers, settings, statistics, recipes, minimum stock, tasks, item lists, build options), plus
    the pages of the builder, warehouse, guard tower, mine, houses, restaurant, farmer, plantation, graveyard,
    enchanter, post box, barracks and nether mine;
  - the university and its research trees, drawn like the advancements screen;
  - Structurize's build tool (blueprint browser, styles, scan, shape, tag and undo tools);
  - Domum Ornamentum's Architect's Cutter (named groups, larger variant grid);
  - the clipboard, the field blocks, the rally banner and every remaining dialog.
- **Resource HUD**: a permanent overlay listing what your builders still need, with the same numbers as the
  Resource Scroll. Hold a scroll and press **J** to pin it, **P** to move the panels, **H** to show or hide them.
- **Locate beams**: a coloured beacon beam above a citizen, a hut or a grave, with its direction and distance.
- **Citizen names**: built-in French and international name lists, or your own (male and female first names,
  surnames), for the new citizens of your colonies.
- **Bug fixes** for a few MineColonies window glitches.

Every screen can be switched off on its own in the config (**K**, or *Mods > MineColonies Vanilla Tweaks > Config*).
When a MineColonies update changes something a screen relies on, that screen switches itself off for the session
and MineColonies' own window is shown instead.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1.251 or newer
- MineColonies 1.1.1368 or newer (and its own dependencies: Structurize, BlockUI, Domum Ornamentum)

Install it in your game's `mods` folder. Servers only need it for custom citizen names and the hungry cook fix (single
player needs nothing more); players and servers without it can still connect.

Languages: English, French.

## Building from source

1. Install JDK 21.
2. Put these jars in `libs/` (from the MineColonies downloads, same file names):
   `minecolonies-1.1.1406-1.21.1-snapshot.jar`, `structurize-1.0.832-1.21.1.jar`, `blockui-1.0.209-1.21.1.jar`,
   `domum-ornamentum-1.0.236-snapshot-main.jar`.
3. Run `./gradlew build`. The mod is written to `build/libs/`, its source archive to `build/distributions/`.

`./gradlew runClient` starts a development client; `./gradlew runPreview` saves a screenshot of each screen,
filled with sample data, to `run/screenshots/`.

## Made with AI

This mod was made with the help of AI tools (Claude, by Anthropic). The ideas, the choices and the in-game testing are the author's; the AI wrote much of the code.

## License

GNU General Public License v3.0 or later, see [LICENSE](LICENSE). MineColonies, Structurize, BlockUI and Domum
Ornamentum are GPL-3 projects by the LDT Team; none of their files are included in this mod.
