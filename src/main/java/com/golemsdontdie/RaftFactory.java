package com.golemsdontdie;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/**
 * Builds the boat drawn under a golem at sea, once: Sailing's raft, assembled from its hull, sail
 * and helm models. See {@link GolemContent#RAFT_HULL_MODEL}.
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
	 * <p>Built once and shared by every golem at sea; safe because the client's transformation
	 * clones vertices rather than writing to its source. Client thread only.
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
			ModelData hull = client.loadModelData(GolemContent.RAFT_HULL_MODEL);
			ModelData mast = client.loadModelData(GolemContent.RAFT_SAIL_MODEL);
			ModelData cloth = client.loadModelData(GolemContent.RAFT_SAIL_CLOTH_MODEL);
			ModelData helm = client.loadModelData(GolemContent.RAFT_HELM_MODEL);
			if (hull == null || mast == null || cloth == null || helm == null)
			{
				log.debug("Raft models not loaded yet");
				searched = false;
				return null;
			}
			// Painted as the objects paint them; the raw models are place-holder purple.
			hull = recolour(hull.cloneVertices().cloneColors(), GolemContent.RAFT_HULL_RECOLOUR_FROM,
				GolemContent.RAFT_HULL_RECOLOUR_TO);
			helm = recolour(helm.cloneVertices().cloneColors(), GolemContent.RAFT_HELM_RECOLOUR_FROM,
				GolemContent.RAFT_HELM_RECOLOUR_TO);
			// Hull, mast and sail sit on the middle tile; the helm is its own tile at the stern.
			helm = helm.translate(0, 0, GolemContent.RAFT_HELM_OFFSET);
			ModelData merged = client.mergeModels(hull, mast.cloneVertices(), cloth.cloneVertices(), helm);
			model = merged.light(BASE_AMBIENT, BASE_CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
			log.debug("Raft model built from hull, sail and helm");
			return model;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build the boat model", e);
			return null;
		}
	}

	private static ModelData recolour(ModelData model, short[] from, short[] to)
	{
		for (int i = 0; i < from.length; i++)
		{
			model.recolor(from[i], to[i]);
		}
		return model;
	}

	void clear()
	{
		model = null;
		searched = false;
	}
}
