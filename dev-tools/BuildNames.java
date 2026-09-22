import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;

/**
 * Harvests the names of the people of Gielinor, for the golems to be called after.
 *
 * <p>Every NPC you can talk to whose name is one word of letters: Zaff, Gertrude, Akthanakos. That
 * is a few thousand, and most of them are people rather than things, because a thing is rarely
 * something you can talk to. What is left over — the bakers, the bankers, the werewolves — is
 * dropped by name below, which is a list that only ever needs adding to.
 *
 * <p>One name per line, gzipped, into {@code src/main/resources/names.gz}. See GolemNames, which
 * reads it, and which holds the other two packs itself: those are written rather than harvested.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/BuildNames.java
 * java  -cp "cache.jar;deps.jar;out" BuildNames [cache-dir] [out.gz]
 * </pre>
 */
public class BuildNames
{
	/**
	 * Names that are a job, a rank or a creature rather than a person. A golem called Baker is a
	 * joke that wears thin at four hundred golems; one called Wurbel is a golem called Wurbel.
	 */
	private static final String BLOCKED = "Academic Afflicted Apothecary Apprentice Archer Assassin Assistant "
		+ "Baker Bandit Banker Barbarian Bard Barmaid Barman Baron Bartender Beaver Beef Beggar Blacksmith "
		+ "Bloodhound Boneguard Bones Boulder Boy Brain Brewer Builder Butler Camel Captain Cat Champion Chaos "
		+ "Chef Chicken Child Citizen Clan Cleaner Clerk Collector Colonel Commander Cook Corporal Councillor "
		+ "Cousin Cow Crab Crone Curator Customer Cyclops Daughter Dealer Demon Deputy Digger Diviner Doctor Dog "
		+ "Doomsayer Dragon Driver Drunk Duck Dwarf Elf Emissary Emperor Engineer Ent Escaped Estate Examiner "
		+ "Fairy Farmer Father Ferryman Fisher Fisherman Fool Foreman Forester Fortune Founder Friend Frog "
		+ "Gardener General Ghost Ghostly Giant Gladiator Gnome Goat Goblin Golem Gorilla Governor Granny "
		+ "Gravedigger Greeter Grim Guard Guardian Guide Guildmaster Gull Hairdresser Handler Harpie Head Healer "
		+ "Herald Herbalist Hermit Hobgoblin Host Hunter Husband Icefiend Imp Innkeeper Instructor Jailer Jailor "
		+ "Jester Judge Kalphite Keeper Kid King Kitten Knight Lackey Lady Leader Lecturer Leprechaun Lesser "
		+ "Librarian Lizardman Lord Loredancer Lumberjack Mage Maid Man Manager Master Mayor Mechanic Merchant "
		+ "Messenger Miller Miner Minion Mistress Monk Monkey Monster Mother Mourner Mugger Mummy Musician Ninja "
		+ "Noble Nomad Novice Nurse Nymph Ogre Operator Oracle Orphan Outlaw Owner Page Painter Panning Peasant "
		+ "Pedlar Penguin Pilgrim Piper Pirate Poet Porter Postie Potter Priest Prince Princess Prisoner "
		+ "Professor Punk Queen Rabbit Raider Rambling Ranger Rat Recruiter Referee Reporter Rider Rogue Sage "
		+ "Sailor Salesman Sandwich Scarab Scavenger Scholar Scout Scribe Sculptor Seer Sentry Servant Shade "
		+ "Shaman Sheep Sheriff Ship Shopkeeper Sister Skeleton Skipper Slave Slayer Smith Snake Soldier "
		+ "Sorcerer Spider Spirit Spy Squire Stonemason Storekeeper Stranger Student Surgeon Survivor Swan "
		+ "Swordsman Tailor Tanner Teacher Terrorbird Tester Thief Tiler Tinker Tortoise Tourist Trader Trainer "
		+ "Tramp Traveller Treasure Tribesman Troll Tutor Undead Urchin Vagrant Vampyre Veteran Vigilante "
		+ "Villager Villain Voice Waiter Wanderer Warden Warlock Warrior Watcher Watchman Weave Weaver Werewolf "
		+ "Wife Wisp Witch Wizard Wolfbone Woman Worker Workman Worm Wounded Wraith Wyrm Yak Yeti Zealot Zombie "
		+ "Cheerleader Grip Aisles Bugs Berry Boot";

	public static void main(String[] args) throws Exception
	{
		File cacheDir = new File(args.length > 0
			? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");
		File out = new File(args.length > 1 ? args[1] : "src/main/resources/names.gz");

		Set<String> blocked = new TreeSet<>();
		for (String word : BLOCKED.split("\\s+"))
		{
			blocked.add(word);
		}

		Set<String> names = new TreeSet<>();
		try (Store store = new Store(cacheDir))
		{
			store.load();
			NpcManager npcs = new NpcManager(store);
			npcs.load();
			for (NpcDefinition npc : npcs.getNpcs())
			{
				String name = npc.getName();
				if (name == null || name.length() < 3 || name.length() > 12 || !name.matches("[A-Z][a-z]+")
					|| blocked.contains(name) || !talkable(npc))
				{
					continue;
				}
				names.add(name);
			}
		}

		try (Writer writer = new OutputStreamWriter(
			new GZIPOutputStream(new FileOutputStream(out)), StandardCharsets.UTF_8))
		{
			for (String name : names)
			{
				writer.write(name);
				writer.write('\n');
			}
		}
		System.out.println("Wrote " + names.size() + " names to " + out.getAbsolutePath());
	}

	/** Whether the NPC can be spoken to, which is most of what tells a person from a thing. */
	private static boolean talkable(NpcDefinition npc)
	{
		net.runelite.cache.EntityOpsDefinition ops = npc.getOps();
		if (ops == null || ops.getOps() == null)
		{
			return false;
		}
		for (net.runelite.cache.EntityOpsDefinition.Op op : ops.getOps())
		{
			if (op != null && op.text != null && op.text.toLowerCase().startsWith("talk"))
			{
				return true;
			}
		}
		return false;
	}
}
