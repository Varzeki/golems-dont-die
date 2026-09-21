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
 * <p>None of it survives the NPC, so a snapshot is kept refreshed for every watched golem
 * and the copy built from the last one before the death animation. Models, recolours, scale
 * and size come from {@link NPCComposition} given only an NPC ID; the animation IDs do not,
 * only the live {@link net.runelite.api.Actor} having them, so those three go to disk.
 */
@Value
class GolemSnapshot
{
	int npcId;
	String name;

	/** Model IDs from the composition, merged and recoloured into the base model. */
	int[] modelIds;
	short[] recolourFrom;
	short[] recolourTo;

	/** Composition scaling, in 128ths. 128 = as authored. */
	int widthScale;
	int heightScale;

	/** Footprint in tiles; sizes the drawn object's tile-sorting radius. */
	int size;

	/** Pose animations from the actor. -1 where the golem has none. */
	int idlePoseAnimation;
	int walkAnimation;
	int runAnimation;

	/** Where it stood and which way it faced when taken. */
	WorldPoint worldLocation;
	int orientation;

	GolemSnapshot facing(int newOrientation)
	{
		return new GolemSnapshot(npcId, name, modelIds, recolourFrom, recolourTo,
			widthScale, heightScale, size, idlePoseAnimation, walkAnimation, runAnimation,
			worldLocation, newOrientation & 2047);
	}

	/** Reads the current state of a live golem; null if the NPC has no usable composition. */
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
	 * Rebuilds a snapshot for a saved golem: appearance from the cache, the rest from the save.
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
		// slightly wrong; saves predating the recording, or catching a transition, carry -1.
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
