![Golems Don't Die](media/golems-dont-die-title.png)

Golems should live forever.

Golem Crafting usually has Golems briefly wander around before dramatically dying of sadness about 20 seconds later. 
This plugin makes them continue to wander around indefinitely instead.

You can even name them.

---

**NEW UPDATE**
![Exploration Expansion: golems sailing, climbing, hopping stones and exploring dungeons](media/exploration-expansion.jpg)

The golems figured out Sailing. They've trained Agility. They've even done quests.

### They explore.

Golems now inherit your characters capabilities - they can access the same agility shortcuts and quest areas you can, and are *mostly* capable of getting there.

They can sail, use shortcuts, activate travel systems such as fairy rings or spirit trees, and even enter instances.




---

## Settings

**Golems**

| Setting | Default | |
|---|---|---|
| Limit golems | Off | Cap golems, for anyone who notices the framerate dip after crafting 1000+ golems. |
| Maximum golems | 25 | The cap, when Limit golems is on. |
| Show golem names | On | Show golem names above their head. |
| Name colour | Yellow (#FFE700) | The colour of the golem names. |
| Golems on the world map | Named and starred golems | Draws golems on the world map. Golems too close together to draw apart become one face with a count. |
| Restrict Golem ambition | Off | Keeps golems on Wyrmscraig. Golems do not sail, and any that are elsewhere return to Wyrmscraig. |
| Auto name golems | Off | Gives golems you have not named one anyway — a name from Gielinor and a surname off the rocks, like Reginald Scree or Megan Gneiss. A golem always gets the same name, and one you type yourself is kept whatever this is set to. |
| Enable sidebar | On | Shows the Golems tab. With it off, missing golems can still be revived by right-clicking a golem plinth. |
| Path to a golem being found | On | While you are finding a golem, the [Shortest Path](https://github.com/Skretzo/shortest-path) plugin draws the way to it. Does nothing without Shortest Path installed. |
| Golems join your ship | On | Golems standing near your boat when you step aboard come too. Not while golem ambition is restricted. |

**Celebrations**

Golems in view stop what they are doing, dance for about ten seconds and set off fireworks.

| Setting | Default | |
|---|---|---|
| Level up | On | Golems dance when you gain a level. |
| Collection log | On | Golems dance when you fill a collection log slot. |
| Golem crafted | Off | Golems dance each time you craft another golem. |
| Quest complete | On | Golems dance when you finish a quest or miniquest. |
| Achievement diary | On | Golems dance when you finish a tier of an achievement diary. |
| Combat achievement | On | Golems dance when you complete a combat task. |
| Pet | On | Golems dance when a pet finds you. |
| Personal best | On | Golems dance when you beat your best time at a boss, a raid or a course. |
| Clue scroll | Off | Golems dance when you finish a clue scroll. |


## Finding a golem

The sidebar lists your golems nearest first, fifteen to a page, with a search box for names. Under
each name is where that golem is — "Catherby", "Taverley Dungeon", "Sailing to Port Khazard".

Star a golem to keep it at the top of the list, wherever it is.

Right-click either carving plinth on Wyrmscraig to revive missing golems without the sidebar. The
option carries the number missing, and is only there while any are.

With **Auto name golems** on, the ones you have not named are called something anyway — dimmed in
the list, and on the golem's own page, so a name you typed still reads as yours. Nothing is written
down: a golem keeps its name because the name comes from the golem, and turning the setting off
leaves them all plain golems again.

**Find** points an arrow at a golem and keeps it there as the golem moves, until you come within
eight tiles of it or stop looking. While you are looking, that golem is the only one on the world
map, its face sticks to the edge of the map when you pan away from it, and an infobox says how far
off it is — right-click it to stop. With Shortest Path installed, the way there is drawn too.

On the map, a named golem wears its name. Hover any face to see where it is.


## Your ship

Golems standing near your boat when you step aboard come with you — starred golems first, then
named ones — and stand at the rail looking out while you sail. They step off where you do. Leave
the boat any other way, by teleport or by logging out, and they go back to the quay they boarded
from.


## Building

```
./gradlew jar
```

Requires a local RuneLite install in `~/.m2` (`./gradlew publishAllToMavenLocal` in the
runelite repo), or `repo.runelite.net` for the published client artifact.


## Credits

Collision map and transport data from [Shortest Path](https://github.com/Skretzo/shortest-path) by Skretzo. 

Model and animation technique from
[Creator's Kit](https://github.com/ScreteMonge/creators-kit) by ScreteMonge. 

Boat movement modelled
on [Turning Circles](https://github.com/anmcgrath/turning-circles) by anmcgrath. 

Dancing contributed by [NathanVegetable](https://github.com/Varzeki/golems-dont-die/pull/1), who took
the idea from [Dance Party](https://github.com/dekvall/runelite-external-plugins/tree/dance-party) by
dekvall. Fireworks after [Death Party](https://github.com/DangItOSRS/death-party) by DangItOSRS. 

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
