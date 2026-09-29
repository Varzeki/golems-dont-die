package com.golemsdontdie;

import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

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
	private final Map<GolemBoat, Model> models = new EnumMap<>(GolemBoat.class);
	private final Set<GolemBoat> searched = EnumSet.noneOf(GolemBoat.class);

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
			// The helm is its own tile at the stern. The rig is not in here: a sail plays an animation
			// of its own, which would fold a hull merged with it, so it is drawn apart; see rigModel.
			helm = helm.translate(0, 0, boat.getHelmOffset());
			List<ModelData> parts = new ArrayList<>();
			parts.add(hull);
			parts.add(helm);
			if (boat.getKeelModel() >= 0)
			{
				ModelData keel = client.loadModelData(boat.getKeelModel());
				if (keel == null)
				{
					log.debug("{} keel not loaded yet", boat);
					searched.remove(boat);
					return null;
				}
				parts.add(recolour(keel.cloneVertices().cloneColors(), GolemBoat.keelFrom(), GolemBoat.keelTo())
					.translate(boat.getKeelX(), 0, boat.getKeelZ()));
			}
			if (boat.getTrimModel() >= 0)
			{
				ModelData trim = client.loadModelData(boat.getTrimModel());
				if (trim == null)
				{
					log.debug("{} trim not loaded yet", boat);
					searched.remove(boat);
					return null;
				}
				parts.add(recolour(trim.cloneVertices().cloneColors(), GolemBoat.trimFrom(), GolemBoat.trimTo()));
			}
			ModelData merged = client.mergeModels(parts.toArray(new ModelData[0]));
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

	/** Mast or cloth, lit and whole: drawn apart from the hull so its sail can play. See FakeRaft. */
	Model rigModel(GolemBoat boat, boolean cloth)
	{
		Map<GolemBoat, Model> cache = cloth ? cloths : masts;
		Model built = cache.get(boat);
		if (built != null)
		{
			return built;
		}
		ModelData data = client.loadModelData(cloth ? boat.getClothModel() : boat.getMastModel());
		if (data == null)
		{
			return null;
		}
		built = data.light(BASE_AMBIENT, BASE_CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
		cache.put(boat, built);
		return built;
	}

	private final Map<GolemBoat, Model> masts = new EnumMap<>(GolemBoat.class);
	private final Map<GolemBoat, Model> cloths = new EnumMap<>(GolemBoat.class);

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
		masts.clear();
		cloths.clear();
	}
}
