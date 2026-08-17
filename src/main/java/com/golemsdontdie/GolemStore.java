package com.golemsdontdie;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/**
 * Reads and writes the saved golem roster.
 *
 * <p>Only what the cache cannot supply is written: the NPC ID, where the golem is,
 * where it calls home, and the three animation IDs that no composition exposes.
 * Everything visual is re-fetched from the cache on load, so the save stays about
 * ten numbers per golem however elaborate the model is.
 *
 * <p>The format is a flat delimited string rather than JSON. The whole record is
 * numbers, the config store takes a string either way, and a save the player can
 * read and edit in the config file is worth more here than a schema.
 */
@Slf4j
@Singleton
class GolemStore
{
	private static final String GOLEM_SEPARATOR = ";";
	private static final String FIELD_SEPARATOR = ",";

	/** Ten numbers plus the nickname. */
	private static final int FIELD_COUNT = 11;

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
	}

	/** Encodes a live roster for the config store. */
	String serialise(List<Golem> golems)
	{
		StringBuilder out = new StringBuilder();
		for (Golem golem : golems)
		{
			GolemSnapshot snapshot = golem.getSnapshot();
			WorldPoint at = golem.currentTile();
			if (out.length() > 0)
			{
				out.append(GOLEM_SEPARATOR);
			}
			out.append(snapshot.getNpcId()).append(FIELD_SEPARATOR)
				.append(at.getX()).append(FIELD_SEPARATOR)
				.append(at.getY()).append(FIELD_SEPARATOR)
				.append(golem.getPlane()).append(FIELD_SEPARATOR)
				.append(golem.getOrientation()).append(FIELD_SEPARATOR)
				.append(golem.getHome().getX()).append(FIELD_SEPARATOR)
				.append(golem.getHome().getY()).append(FIELD_SEPARATOR)
				.append(snapshot.getIdlePoseAnimation()).append(FIELD_SEPARATOR)
				.append(snapshot.getWalkAnimation()).append(FIELD_SEPARATOR)
				.append(snapshot.getRunAnimation()).append(FIELD_SEPARATOR)
				// Separators are stripped rather than escaped: a nickname is free text
				// the player types, and losing a stray comma from one is a far smaller
				// problem than a save file that will not parse.
				.append(golem.getNickname() == null
					? ""
					: golem.getNickname().replaceAll(ILLEGAL_IN_NICKNAME, ""));
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
			if (fields.length != FIELD_COUNT)
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
	 * @param index position in the save file, which is what keeps each golem's seed
	 *              distinct — see below
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

		// The index is what makes the seed unique, and it is not decoration.
		//
		// Position alone is not enough: two golems standing on the same tile when the
		// game was saved would come back with the same seed, and a Golem's entire gait
		// — where it wanders, how long it dwells — comes out of that one number. They
		// would walk in perfect lockstep forever, which is both obviously wrong and
		// exactly what happens where golems congregate, such as on a spawn tile.
		//
		// Position still contributes, so a golem keeps a stable character across
		// reloads as long as the roster does not change around it.
		long seed = ((long) saved.worldX << 32) ^ ((long) saved.worldY << 8) ^ saved.npcId ^ (index * 0x9E3779B9L);
		Golem golem = Golem.onTile(snapshot, home, seed, at);
		golem.setNickname(saved.nickname);
		return golem;
	}
}
