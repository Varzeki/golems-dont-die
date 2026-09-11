package com.golemsdontdie;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.gameval.DBTableID;

/**
 * Finds a boat to draw under a golem that is at sea, and builds its model once.
 *
 * <p>The hull has to come from an NPC rather than a scene object, for the same reason the
 * props do in reverse: {@code NPCComposition} exposes model ids and
 * {@code ObjectComposition} does not. Sailing's crewed vessels are NPCs, so the geometry
 * is reachable at runtime through exactly the machinery {@link GolemModelFactory} already
 * uses.
 *
 * <p>Which NPC is not hardcoded. The boat table's columns are unnamed in the client API,
 * so rather than guess a column index and silently draw a seagull, every integer in the
 * table is tried as an NPC id and kept only if it resolves to a composition that has
 * models and is named like a vessel. A wrong guess then fails to find anything and golems
 * sail without a visible boat, which is a much better failure than the wrong model.
 */
@Slf4j
@Singleton
class RaftFactory
{
	/** The client's own lighting for an actor, matching {@link GolemModelFactory}. */
	private static final int BASE_AMBIENT = 64;
	private static final int BASE_CONTRAST = 850;
	private static final int LIGHT_X = -30;
	private static final int LIGHT_Y = -50;
	private static final int LIGHT_Z = -30;

	/** Words that mark a composition as a vessel rather than its crew. */
	private static final String[] VESSEL_WORDS = {"boat", "raft", "ship", "vessel", "dinghy"};

	@Inject
	private Client client;

	private Model model;
	private boolean searched;

	/**
	 * The raft model, or null if none could be found.
	 *
	 * <p>Built once and shared by every golem at sea. Safe to share for the usual reason:
	 * the client's transformation clones vertices out of its source rather than writing to
	 * it.
	 *
	 * <p>Must be called on the client thread.
	 */
	Model raftModel()
	{
		if (searched)
		{
			return model;
		}
		searched = true;

		int npcId = findBoatNpc();
		if (npcId < 0)
		{
			log.debug("No boat NPC found; golems will sail without a visible hull");
			return null;
		}

		try
		{
			NPCComposition composition = client.getNpcDefinition(npcId);
			int[] models = composition.getModels();
			if (models == null || models.length == 0)
			{
				return null;
			}

			ModelData merged;
			if (models.length == 1)
			{
				ModelData one = client.loadModelData(models[0]);
				merged = one == null ? null : one.cloneVertices();
			}
			else
			{
				ModelData[] parts = new ModelData[models.length];
				for (int i = 0; i < models.length; i++)
				{
					parts[i] = client.loadModelData(models[i]);
					if (parts[i] == null)
					{
						return null;
					}
				}
				merged = client.mergeModels(parts).cloneVertices();
			}

			if (merged == null)
			{
				return null;
			}
			model = merged.light(BASE_AMBIENT, BASE_CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
			log.debug("Raft model built from NPC {} ({})", npcId, composition.getName());
			return model;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build a raft model", e);
			return null;
		}
	}

	/**
	 * An NPC id from the boat table that actually resolves to a vessel.
	 *
	 * <p>Every integer in every column is a candidate, because the columns are unnamed.
	 * The filter is what makes that safe: a candidate has to resolve to a real composition,
	 * have models, and be named like a boat. Levels, sprite ids and row references fail all
	 * three.
	 */
	private int findBoatNpc()
	{
		List<Integer> rows;
		try
		{
			rows = client.getDBTableRows(DBTableID.SailingNpcBoat.ID);
		}
		catch (RuntimeException e)
		{
			return -1;
		}
		if (rows == null)
		{
			return -1;
		}

		for (int row : rows)
		{
			for (int column = 0; column < 8; column++)
			{
				Object[] values;
				try
				{
					values = client.getDBTableField(row, column, 0);
				}
				catch (RuntimeException e)
				{
					continue;
				}
				if (values == null || values.length == 0 || !(values[0] instanceof Integer))
				{
					continue;
				}

				int candidate = (Integer) values[0];
				if (candidate <= 0 || candidate > 65535)
				{
					continue;
				}
				if (isVessel(candidate))
				{
					return candidate;
				}
			}
		}
		return -1;
	}

	private boolean isVessel(int npcId)
	{
		try
		{
			NPCComposition composition = client.getNpcDefinition(npcId);
			if (composition == null || composition.getName() == null
				|| composition.getModels() == null || composition.getModels().length == 0)
			{
				return false;
			}
			String name = composition.getName().toLowerCase();
			for (String word : VESSEL_WORDS)
			{
				if (name.contains(word))
				{
					return true;
				}
			}
			return false;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	void clear()
	{
		model = null;
		searched = false;
	}
}
