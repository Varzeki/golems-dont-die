package com.golemsdontdie;

import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;

/**
 * Everything about a live golem that a copy will need, recorded while the real one
 * is still standing.
 *
 * <p>It has to be gathered in advance because none of it survives the NPC. Once the
 * golem despawns it is gone from the scene and the animation IDs the client was
 * posing it with go with it — so the plugin keeps a snapshot refreshed for every
 * golem it is watching, and builds the copy from the last one taken before the death
 * animation began.
 *
 * <p>The split between what is looked up and what is remembered matters for saving.
 * Models, recolours, scale and size all come from {@link NPCComposition}, so they can
 * be re-fetched from the cache later given only an NPC ID. The animation IDs cannot:
 * the RuneLite API's composition exposes no standing or walking animation, so there
 * is no way to ask the cache how an NPC walks. Only the live
 * {@link net.runelite.api.Actor}, which has already been told, will answer — which is
 * why those three ints are the part that has to be written to disk.
 */
@Value
class GolemSnapshot
{
	int npcId;
	String name;

	/** Model IDs from the composition, merged and recoloured to build the base model. */
	int[] modelIds;
	short[] recolourFrom;
	short[] recolourTo;

	/** Composition scaling, in 128ths. 128 = as authored. */
	int widthScale;
	int heightScale;

	/** Footprint in tiles, used to size the drawn object's tile-sorting radius. */
	int size;

	/** Pose animations, harvested from the actor. -1 where the golem has none. */
	int idlePoseAnimation;
	int walkAnimation;
	int runAnimation;

	/** Where it stood and which way it faced when the snapshot was taken. */
	WorldPoint worldLocation;
	int orientation;

	/** A copy of this snapshot facing somewhere else. */
	GolemSnapshot facing(int newOrientation)
	{
		return new GolemSnapshot(npcId, name, modelIds, recolourFrom, recolourTo,
			widthScale, heightScale, size, idlePoseAnimation, walkAnimation, runAnimation,
			worldLocation, newOrientation & 2047);
	}

	/**
	 * Reads the current state of a live golem.
	 *
	 * @return the snapshot, or null if the NPC has no usable composition
	 */
	static GolemSnapshot of(NPC npc)
	{
		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}
		if (comp == null)
		{
			return null;
		}

		return build(
			comp,
			npc.getName() == null ? comp.getName() : npc.getName(),
			npc.getIdlePoseAnimation(),
			npc.getWalkAnimation(),
			npc.getRunAnimation(),
			npc.getWorldLocation(),
			npc.getCurrentOrientation());
	}

	/**
	 * Rebuilds a snapshot for a saved golem, taking appearance from the cache and
	 * everything the cache does not hold from the save.
	 *
	 * @return the snapshot, or null if the NPC ID is no longer in the cache
	 */
	static GolemSnapshot restore(Client client, int npcId, int idlePose, int walk, int run,
		WorldPoint where, int orientation)
	{
		NPCComposition comp = client.getNpcDefinition(npcId);
		if (comp == null)
		{
			return null;
		}

		// A golem with no animation stands frozen, which is worse than one moving
		// slightly wrong. Saves written before the animations were recorded, or ones
		// that caught a golem mid-transition, can carry -1 — so fall back to the known
		// values rather than restoring a statue.
		if (walk == -1)
		{
			walk = GolemContent.GOLEM_WALK_ANIMATION;
		}
		if (idlePose == -1)
		{
			idlePose = GolemContent.GOLEM_IDLE_ANIMATION;
		}

		return build(comp, comp.getName(), idlePose, walk, run, where, orientation);
	}

	private static GolemSnapshot build(NPCComposition comp, String name, int idlePose, int walk, int run,
		WorldPoint where, int orientation)
	{
		int[] models = comp.getModels();
		if (models == null || models.length == 0)
		{
			return null;
		}

		return new GolemSnapshot(
			comp.getId(),
			name == null ? "Golem" : name,
			models.clone(),
			comp.getColorToReplace() == null ? new short[0] : comp.getColorToReplace().clone(),
			comp.getColorToReplaceWith() == null ? new short[0] : comp.getColorToReplaceWith().clone(),
			comp.getWidthScale(),
			comp.getHeightScale(),
			Math.max(1, comp.getSize()),
			idlePose,
			walk,
			run,
			where,
			orientation & 2047);
	}
}
