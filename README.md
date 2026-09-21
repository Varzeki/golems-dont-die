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
| Restrict Golem ambition | Off | Keeps golems on Wyrmscraig. Golems do not sail, and any that are elsewhere return to Wyrmscraig. |


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

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
