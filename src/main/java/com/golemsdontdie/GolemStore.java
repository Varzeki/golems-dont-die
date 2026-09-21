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
 * <p>Only what the cache cannot supply is written: the NPC ID, position, home tile, and the
 * three animation IDs no composition exposes. Everything visual is re-fetched on load, so the
 * save stays about ten numbers per golem. A flat delimited string rather than JSON: the record
 * is all numbers, and a save the player can read and edit is worth more than a schema.
 */
@Slf4j
@Singleton
class GolemStore
{
	private static final String GOLEM_SEPARATOR = ";";
	private static final String FIELD_SEPARATOR = ",";

	/**
	 * Ten numbers plus the nickname. Two more are optional: whether the golem is in an
	 * instance, and when it may next sail.
	 */
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
		boolean inInstance;
		long shoreLeaveUntil;
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
				.append(FIELD_SEPARATOR).append(golem.getShoreLeaveUntil());
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
			if (fields.length < FIELD_COUNT || fields.length > FIELD_COUNT + 2)
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

		// The index is what makes the seed unique. Position alone is not enough: two golems
		// saved on the same tile — what happens on a spawn tile — would share a seed, and a
		// Golem's entire gait comes out of that one number. Position still contributes, so a
		// golem keeps a stable character across reloads.
		long seed = ((long) saved.worldX << 32) ^ ((long) saved.worldY << 8) ^ saved.npcId ^ (index * 0x9E3779B9L);
		Golem golem = Golem.onTile(snapshot, home, seed, at);
		golem.setNickname(saved.nickname);
		golem.setInInstance(saved.inInstance);
		golem.setShoreLeaveUntil(saved.shoreLeaveUntil);
		return golem;
	}
}
