package com.golemsdontdie;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/**
 * Builds the boats drawn under golems at sea, one of each kind: hull, mast, sail and helm, each
 * assembled once and shared by everyone sailing that size. See {@link GolemBoat}.
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

	/** One built model per kind of boat, and whether building it has been tried. */
	private final java.util.Map<GolemBoat, Model> models = new java.util.EnumMap<>(GolemBoat.class);
	private final java.util.Set<GolemBoat> searched = java.util.EnumSet.noneOf(GolemBoat.class);

	/** The raft, which is what a golem sailing alone takes. */
	Model raftModel()
	{
		return boatModel(GolemBoat.RAFT);
	}

	/**
	 * A boat's model, or null if it could not be built.
	 *
	 * <p>Built once per kind and shared by everyone sailing one; safe because the client's
	 * transformation clones vertices rather than writing to its source. Client thread only.
	 */
	Model boatModel(GolemBoat boat)
	{
		Model built = models.get(boat);
		if (built != null || !searched.add(boat))
		{
			return built;
		}

		try
		{
			ModelData hull = client.loadModelData(boat.getHullModel());
			ModelData mast = client.loadModelData(boat.getMastModel());
			ModelData cloth = client.loadModelData(boat.getClothModel());
			ModelData helm = client.loadModelData(GolemContent.RAFT_HELM_MODEL);
			if (hull == null || mast == null || cloth == null || helm == null)
			{
				log.debug("{} models not loaded yet", boat);
				searched.remove(boat);
				return null;
			}
			// Painted as the objects paint them; the raw models are place-holder purple.
			hull = recolour(hull.cloneVertices().cloneColors(), boat.getHullFrom(), boat.getHullTo());
			helm = recolour(helm.cloneVertices().cloneColors(), GolemContent.RAFT_HELM_RECOLOUR_FROM,
				GolemContent.RAFT_HELM_RECOLOUR_TO);
			// Hull, mast and sail sit on the middle tile; the helm is its own tile at the stern.
			helm = helm.translate(0, 0, boat.getHelmOffset());
			ModelData merged = client.mergeModels(hull, mast.cloneVertices(), cloth.cloneVertices(), helm);
			Model made = merged.light(BASE_AMBIENT, BASE_CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
			models.put(boat, made);
			log.debug("{} built from hull, sail and helm", boat);
			return made;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build {}", boat, e);
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
		models.clear();
		searched.clear();
	}
}
