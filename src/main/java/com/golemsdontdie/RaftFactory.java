package com.golemsdontdie;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;

/**
 * Builds the boat drawn under a golem at sea, once.
 *
 * <p>The hull has to come from an NPC rather than a scene object, for the same reason the
 * props do in reverse: {@code NPCComposition} exposes model ids and
 * {@code ObjectComposition} does not.
 *
 * <p>Which NPC is a measured constant, {@link GolemContent#RAFT_NPC}. This used to search
 * Sailing's boat table at runtime for anything named like a vessel, and found nothing in
 * any session: the table lists boat crews, not boats. See the constant for how the hull was
 * found.
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

	@Inject
	private Client client;

	private Model model;
	private boolean searched;

	/**
	 * The boat model, or null if it could not be built.
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

		try
		{
			NPCComposition composition = client.getNpcDefinition(GolemContent.RAFT_NPC);
			int[] models = composition == null ? null : composition.getModels();
			if (models == null || models.length == 0)
			{
				log.warn("Boat NPC {} has no models; golems will sail without a visible hull", GolemContent.RAFT_NPC);
				return null;
			}

			ModelData[] parts = new ModelData[models.length];
			for (int i = 0; i < models.length; i++)
			{
				parts[i] = client.loadModelData(models[i]);
				if (parts[i] == null)
				{
					log.debug("Boat model {} not loaded yet", models[i]);
					searched = false;
					return null;
				}
			}
			ModelData merged = parts.length == 1 ? parts[0].cloneVertices() : client.mergeModels(parts).cloneVertices();
			model = merged.light(BASE_AMBIENT, BASE_CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
			log.debug("Boat model built from NPC {} ({})", GolemContent.RAFT_NPC, composition.getName());
			return model;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build the boat model", e);
			return null;
		}
	}

	void clear()
	{
		model = null;
		searched = false;
	}
}
