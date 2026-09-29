package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/**
 * Reads and writes the saved golem roster.
 *
 * <p>Only what the cache cannot supply is written: the NPC ID, position, home tile, the three
 * animation IDs no composition exposes, and what the golem has been and done — its seed, traits,
 * history and journal. Everything visual is re-fetched on load, so the save stays a couple of
 * dozen numbers per golem. A flat delimited string rather than JSON: the record
 * is all numbers, and a save the player can read and edit is worth more than a schema.
 */
@Slf4j
@Singleton
class GolemStore
{
	private static final String GOLEM_SEPARATOR = ";";
	private static final String FIELD_SEPARATOR = ",";

	/**
	 * Ten numbers plus the nickname. The rest are optional: whether the golem is in an instance,
	 * when it may next sail, its seed, the six of its history, its traits, its star, its craft
	 * number and its journal.
	 *
	 * <p>The format only ever grows at the end, and a reader takes what it knows and ignores the
	 * rest. So a save written by a later version still loads here, less whatever was added since;
	 * refusing it, as 2.0 does anything past thirteen fields, costs a player every golem they have
	 * the first time two versions meet.
	 */
	private static final int FIELD_COUNT = 11;

	/** Where the history starts among the optional fields. */
	private static final int HISTORY_AT = FIELD_COUNT + 3;

	/** Where the traits are, after the six of the history. */
	private static final int TRAITS_AT = HISTORY_AT + 6;

	/** Whether the player starred the golem, after its traits. */
	private static final int FAVOURITE_AT = TRAITS_AT + 1;

	/** Its craft number, after the star. */
	private static final int CRAFT_AT = FAVOURITE_AT + 1;

	/** Its journal, after the craft number; see GolemHistory#journalCode. */
	private static final int JOURNAL_AT = CRAFT_AT + 1;

	/** Characters a nickname may not contain, because they are the separators. */
	private static final String ILLEGAL_IN_NICKNAME = "[;,]";

	@Inject
	private Client client;

	/** A golem as it sits in the save file, before the cache fills in its appearance. */
	static class SavedGolem
	{
		int npcId;
		int worldX;
		int worldY;
		int plane;
		int orientation;
		int homeX;
		int homeY;
		int idlePose;
		int walk;
		int run;
		String nickname;
		boolean inInstance;
		long shoreLeaveUntil;

		/** The golem's own number, or 0 for a save written before it was kept. See revive. */
		long seed;

		/**
		 * Its traits as dealt, or 0 for a save written before they were kept. Kept because they are
		 * drawn from the trait list as it stands, and the list will not stand still: re-dealt from
		 * the seed after the list changed, every golem would come back with somebody else's.
		 */
		int traits;

		/** Starred by the player; see Golem#isFavourite. */
		boolean favourite;

		/** See Golem#getCraftNumber; 0 for a save written before it was kept, numbered on restore. */
		int craftNumber;

		/** The journal as GolemHistory#journalCode wrote it; null for a save written before it was kept. */
		String journal;

		/** Its history, in the order GolemHistory.restore takes them; all zero if there was none. */
		long firstSeen;
		int transports;
		int voyages;
		int walked;
		int furthestX;
		int furthestY;
	}

	/** Encodes a live roster for the config store. */
	String serialise(List<Golem> golems)
	{
		StringBuilder out = new StringBuilder();
		for (Golem golem : golems)
		{
			GolemSnapshot snapshot = golem.getSnapshot();
			WorldPoint at = golem.saveTile();
			if (out.length() > 0)
			{
				out.append(GOLEM_SEPARATOR);
			}
			out.append(snapshot.getNpcId()).append(FIELD_SEPARATOR)
				.append(at.getX()).append(FIELD_SEPARATOR)
				.append(at.getY()).append(FIELD_SEPARATOR)
				.append(at.getPlane()).append(FIELD_SEPARATOR)
				.append(golem.getOrientation()).append(FIELD_SEPARATOR)
				.append(golem.getHome().getX()).append(FIELD_SEPARATOR)
				.append(golem.getHome().getY()).append(FIELD_SEPARATOR)
				.append(snapshot.getIdlePoseAnimation()).append(FIELD_SEPARATOR)
				.append(snapshot.getWalkAnimation()).append(FIELD_SEPARATOR)
				.append(snapshot.getRunAnimation()).append(FIELD_SEPARATOR)
				// Separators are stripped rather than escaped: losing a stray comma from
				// free text beats a save file that will not parse.
				.append(golem.getNickname() == null
					? ""
					: golem.getNickname().replaceAll(ILLEGAL_IN_NICKNAME, ""))
				.append(FIELD_SEPARATOR).append(golem.isInInstance() ? 1 : 0)
				// Real time, so a golem that landed just before logout is still ashore after it.
				.append(FIELD_SEPARATOR).append(golem.getShoreLeaveUntil())
				.append(FIELD_SEPARATOR).append(golem.getId());
			GolemHistory history = golem.getHistory();
			out.append(FIELD_SEPARATOR).append(history.getFirstSeen())
				.append(FIELD_SEPARATOR).append(history.getTransports())
				.append(FIELD_SEPARATOR).append(history.getVoyages())
				.append(FIELD_SEPARATOR).append(history.getWalked())
				.append(FIELD_SEPARATOR).append(history.getFurthestX())
				.append(FIELD_SEPARATOR).append(history.getFurthestY())
				.append(FIELD_SEPARATOR).append(golem.getTraits())
				.append(FIELD_SEPARATOR).append(golem.isFavourite() ? 1 : 0)
				.append(FIELD_SEPARATOR).append(golem.getCraftNumber())
				.append(FIELD_SEPARATOR).append(history.journalCode());
		}
		return out.toString();
	}

	/** Decodes a roster. Malformed entries are dropped rather than failing the load. */
	List<SavedGolem> deserialise(String encoded)
	{
		List<SavedGolem> result = new ArrayList<>();
		if (encoded == null || encoded.trim().isEmpty())
		{
			return result;
		}

		for (String entry : encoded.split(GOLEM_SEPARATOR))
		{
			if (entry.trim().isEmpty())
			{
				continue;
			}
			// -1 keeps a trailing empty nickname as a field rather than dropping it.
			String[] fields = entry.split(FIELD_SEPARATOR, -1);
			// A floor and no ceiling: fields past the ones this version knows are a later
			// version's, and are ignored rather than taken as damage.
			if (fields.length < FIELD_COUNT)
			{
				log.debug("Dropping malformed saved golem '{}'", entry);
				continue;
			}
			try
			{
				SavedGolem saved = new SavedGolem();
				saved.npcId = Integer.parseInt(fields[0].trim());
				saved.worldX = Integer.parseInt(fields[1].trim());
				saved.worldY = Integer.parseInt(fields[2].trim());
				saved.plane = Integer.parseInt(fields[3].trim());
				saved.orientation = Integer.parseInt(fields[4].trim());
				saved.homeX = Integer.parseInt(fields[5].trim());
				saved.homeY = Integer.parseInt(fields[6].trim());
				saved.idlePose = Integer.parseInt(fields[7].trim());
				saved.walk = Integer.parseInt(fields[8].trim());
				saved.run = Integer.parseInt(fields[9].trim());
				String nickname = fields[10].trim();
				saved.nickname = nickname.isEmpty() ? null : nickname;
				saved.inInstance = fields.length > FIELD_COUNT && "1".equals(fields[FIELD_COUNT].trim());
				saved.shoreLeaveUntil = fields.length > FIELD_COUNT + 1 ? Long.parseLong(fields[FIELD_COUNT + 1].trim()) : 0;
				saved.seed = fields.length > FIELD_COUNT + 2 ? Long.parseLong(fields[FIELD_COUNT + 2].trim()) : 0;
				if (fields.length >= HISTORY_AT + 6)
				{
					saved.firstSeen = Long.parseLong(fields[HISTORY_AT].trim());
					saved.transports = Integer.parseInt(fields[HISTORY_AT + 1].trim());
					saved.voyages = Integer.parseInt(fields[HISTORY_AT + 2].trim());
					saved.walked = Integer.parseInt(fields[HISTORY_AT + 3].trim());
					saved.furthestX = Integer.parseInt(fields[HISTORY_AT + 4].trim());
					saved.furthestY = Integer.parseInt(fields[HISTORY_AT + 5].trim());
				}
				saved.traits = fields.length > TRAITS_AT ? Integer.parseInt(fields[TRAITS_AT].trim()) : 0;
				saved.favourite = fields.length > FAVOURITE_AT && "1".equals(fields[FAVOURITE_AT].trim());
				saved.craftNumber = fields.length > CRAFT_AT ? Integer.parseInt(fields[CRAFT_AT].trim()) : 0;
				saved.journal = fields.length > JOURNAL_AT ? fields[JOURNAL_AT].trim() : null;
				result.add(saved);
			}
			catch (NumberFormatException e)
			{
				log.debug("Dropping unparseable saved golem '{}'", entry, e);
			}
		}
		return result;
	}

	/**
	 * Turns a saved record back into a golem, pulling its appearance from the cache.
	 *
	 * @param index position in the save file, which keeps each golem's seed distinct
	 * @return the golem, or null if the cache no longer knows that NPC ID
	 */
	Golem revive(SavedGolem saved, int index)
	{
		WorldPoint at = new WorldPoint(saved.worldX, saved.worldY, saved.plane);
		GolemSnapshot snapshot = GolemSnapshot.restore(
			client, saved.npcId, saved.idlePose, saved.walk, saved.run, at, saved.orientation);
		if (snapshot == null)
		{
			log.debug("Saved golem npc {} is no longer in the cache", saved.npcId);
			return null;
		}

		WorldPoint home = new WorldPoint(saved.homeX, saved.homeY, saved.plane);

		// A golem's seed is its character: its gait comes out of it, and so do its traits. It is
		// saved with the golem for that reason — worked out from where it stood, it changed every
		// time the golem moved, and a golem came back a different one.
		//
		// Saves written before it was kept have none, and one is made for them the old way: from
		// the position and the index, the index because two golems on one tile — which is what a
		// spawn tile gives you — would otherwise share a seed and a gait.
		long seed = saved.seed != 0 ? saved.seed
			: ((long) saved.worldX << 32) ^ ((long) saved.worldY << 8) ^ saved.npcId ^ (index * 0x9E3779B9L);
		Golem golem = Golem.onTile(snapshot, home, seed, at);
		// Every golem is dealt at least one trait, so none at all means none were saved: the golem
		// keeps the hand its seed deals today, which from now on is saved with it.
		if (saved.traits != 0)
		{
			golem.restoreTraits(saved.traits);
		}
		golem.setNickname(saved.nickname);
		golem.setFavourite(saved.favourite);
		golem.setCraftNumber(saved.craftNumber);
		if (saved.firstSeen != 0)
		{
			golem.getHistory().restore(saved.firstSeen, saved.transports, saved.voyages, saved.walked,
				saved.furthestX, saved.furthestY, home);
		}
		golem.getHistory().restoreJournal(saved.journal);
		golem.setInInstance(saved.inInstance);
		golem.setShoreLeaveUntil(saved.shoreLeaveUntil);
		return golem;
	}
}
