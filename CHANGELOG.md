# Changelog

## Unreleased

- Fixed the build tool falling back to Structurize's windows when browsing leveled buildings before a preview
  position has been set.
- Builder's resources: a "Supply all" button hands over all needed items carried in the player's inventory.
  Like the individual give buttons, it only works near the hut and not from the resource scroll.
- Citizen skills (creative mode): select a skill and hold Shift to show controls for adjusting it by 5 or setting
  it to the minimum (1) or maximum (99). The usual per-skill +/- buttons stay visible; the extra controls fit
  inside the panel.
- Updated development dependencies to MineColonies 1.1.1406, Structurize 1.0.837 and BlockUI 1.0.212.
- Repository cleanup: ignore generated output, local dependency jars, development game data and editor settings;
  make the Gradle launcher executable.

## 1.5.11

- Multi-Piston (the block that moves doors and walls in MineColonies buildings): its settings window is now a
  vanilla-style screen too. Each face shows its colour and where it points ("Yellow (top)"); picking the face the
  other side uses swaps them instead of showing an error; range and speed are sliders. Players without Multi-Piston
  are not affected.

## 1.5.10

- Fixed a MineColonies bug: the restaurant's cook could starve. Nobody serves the cook, and when his restaurant held
  nothing he was allowed to eat (only raw food, or food too plain for his house level), he kept trying to eat
  instead of cooking. Now he eats the best cooked food he finds in his inventory or his restaurant, even if his
  house expects better (his food happiness still counts it), then goes back to work; with nothing cooked at all,
  he cooks first. Needs the mod on the server (single player: nothing more to do).

## 1.5.9

- Updated for MineColonies 1.1.1403 (1.1.1368 still works). The "fulfill" button of a citizen's requests works again
  with 1.1.1403.
- Colony guide (key G): the buildings of the colony you stand in, yours or another player's. A list with level,
  distance and direction (sort by distance or name, search), and a plan seen from above with the ground drawn like a
  map and the players present. A click on a building puts a beacon beam on it. Entering someone else's colony now says
  how many buildings it has and which key shows them (can be turned off in K > Beams).
- Builder's work orders: the order the builder works on now comes first, outlined in green and marked "in progress",
  like MineColonies 1.1.1403; free orders out of the builder's reach no longer show in manual mode.
- Graveyard: the buried column shows how many plots are filled (MineColonies 1.1.1403).
- The clipboard's building list shows the direction of each building.
- New project icon.

## 1.5.8

- Builders' "building finished" messages (also repairs and deconstructions) can now be clicked: a beacon beam rises
  above the building (with its distance and direction at the top of the screen) and its coordinates and direction are
  written in the chat. Click again to remove the beam. Hovering the message says what a click does.
- Choose what the click does in the settings (K > Beams > Click on "building finished"): beam and coordinates, beam
  only, coordinates only, or nothing. Works on servers without the mod.

## 1.5.7

- Citizen names: each of your lists can have its own icon. Open the list and press "Icon": every item of the game
  (modded ones too) in a grid with a search field; "Default icon" puts the book and quill back.
- Import: the new "Import..." button adds lists from .json files (or drop the files on the window). The file holds a
  "name", an optional "icon" and the "male", "female" and "surnames" names; French keys ("hommes", "femmes", "noms")
  work too, and a mistake is shown with its line number.
- Export: "Export to a file..." in a list saves it as a .json file, to keep, edit in a text editor or share. Built-in
  lists can be exported too: an easy start for your own file.

## 1.5.6

- Citizen names: the list screen now looks like the game's own menus (like Select World), and each list opens in a
  screen with tabs (like Create World): the list's name and use, then its male first names, female first names and
  surnames, with Add and Remove buttons. Deleting a list asks for confirmation; "Example" shows a few drawn names.
- Search field: finds lists by their name or by a name they hold, accents ignored.
- Lists are grouped by category (World, History, Fantasy, My Lists); a click on a category opens or closes it.
- New built-in lists: Medieval, Ancient Rome, Norse, Elves, Dwarves and Orcs.

## 1.5.5

- Citizen names: pick the lists your new citizens take their names from (settings, K > Citizen names > Edit the
  lists...). Built-in French, English, Spanish, Italian, German and Japanese lists, and your own lists with male first
  names, female first names and surnames, kept in your config folder. Several lists can be ticked at once; names
  already carried in the colony are avoided, and children keep a parent's surname. Nothing is ticked by default, so
  MineColonies' names stay until you choose.
- Custom names need the mod on the server as well (in single player there is nothing more to do). Players and
  servers without it still connect: their citizens keep MineColonies' names.

## 1.5.4

- Resource HUD: the construction line keeps the space before the level ("Upgrading Hospital 2 > 3" instead of
  "Hospital2 > 3").

## 1.5.3

- Builder's resources page: the materials of Domum Ornamentum blocks show their name ("Brick Extra") instead of a
  raw "block.domum_ornamentum..." code.
- Town hall: the style button shows the style's name in full; "Style: ..." appears on hover.
- Hut home page: couriers at work get a citizen icon in front of their name instead of an empty space.

## 1.5.2 - first public release

- Vanilla-style screens for every MineColonies window a player can reach: citizens, town hall, huts, university and
  research trees, build tool, Architect's Cutter, clipboard, colony map, quest log and dialogs.
- Resource HUD for pinned builders (J to pin, P to arrange, H to toggle).
- Locate beams for citizens, huts and graves.
- Settings (K) organised in menus: Screens, Resource HUD (Position and size, Content) and Beams; every screen can be
  switched off on its own.
- On the builder's resources page, the missing items can be given from your inventory ("+") only at the builder's
  hut, opened from its block: not from the Resource Scroll.
