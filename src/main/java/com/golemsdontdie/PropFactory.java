package com.golemsdontdie;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/**
 * Builds the animated scenery drawn beside a golem that is using something.
 *
 * <p>{@code ObjectComposition} exposes an object's name, actions and size but <b>not its
 * model ids</b>, where {@code NPCComposition} does, so there is no runtime route from an
 * object id to its geometry. The ids are read offline from the game cache and shipped: 206
 * objects the transport network
 * references, in about a kilobyte. Each carries the animation the object itself plays, so a
 * gate, a portcullis and a fairy ring each get their own motion with nothing enumerating
 * which is which.
 *
 * <p>Models are cached and shared as the golems' are: a hundred golems boarding the same
 * ferry build one plank between them.
 */
@Slf4j
@Singleton
class PropFactory
{
	private static final String RESOURCE = "/props.gz";

	/** The client's own lighting for a scene object, before the object's own offsets. */
	private static final int BASE_AMBIENT = 64;
	private static final int BASE_CONTRAST = 768;
	private static final int LIGHT_X = -50;
	private static final int LIGHT_Y = -10;
	private static final int LIGHT_Z = -50;

	@Inject
	private Client client;

	/** What the cache says about one animated object. */
	static final class Prop
	{
		@Getter
		private final int objectId;
		@Getter
		private final int animation;
		private final int ambient;
		private final int contrast;
		@Getter
		private final int sizeX;
		@Getter
		private final int sizeY;
		private final int[] models;

		Prop(int objectId, int animation, int ambient, int contrast,
			int sizeX, int sizeY, int[] models)
		{
			this.objectId = objectId;
			this.animation = animation;
			this.ambient = ambient;
			this.contrast = contrast;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.models = models;
		}
	}

	private final Map<Integer, Prop> props = new HashMap<>();
	private final Map<Integer, Model> built = new HashMap<>();

	private boolean loaded;

	void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;

		try (InputStream raw = PropFactory.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.debug("No prop table in the jar; scenery will not be animated");
				return;
			}
			try (GZIPInputStream gz = new GZIPInputStream(raw);
				 DataInputStream data = new DataInputStream(gz))
			{
				int count = data.readInt();
				for (int i = 0; i < count; i++)
				{
					int objectId = data.readInt();
					int animation = data.readInt();
					int ambient = data.readShort();
					int contrast = data.readShort();
					int sizeX = data.readByte();
					int sizeY = data.readByte();
					int[] models = new int[data.readByte() & 0xFF];
					for (int m = 0; m < models.length; m++)
					{
						models[m] = data.readInt();
					}
					props.put(objectId, new Prop(objectId, animation, ambient, contrast,
						sizeX, sizeY, models));
				}
				log.debug("Loaded {} animated props", props.size());
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Prop table unreadable", e);
			props.clear();
		}
	}

	/** Null if the object has no animation worth drawing. */
	Prop propFor(int objectId)
	{
		return props.get(objectId);
	}

	/**
	 * The object's model, built once and shared. Sharing is safe because the client's
	 * transformation clones vertices out of its source, so the base is never written to.
	 *
	 * @return the lit model, or null if the cache would not give up the parts
	 */
	Model modelFor(Prop prop)
	{
		if (prop == null)
		{
			return null;
		}
		Model cached = built.get(prop.getObjectId());
		if (cached != null)
		{
			return cached;
		}

		try
		{
			ModelData merged = merge(prop.models);
			if (merged == null)
			{
				return null;
			}
			Model model = merged.light(BASE_AMBIENT + prop.ambient,
				BASE_CONTRAST + prop.contrast, LIGHT_X, LIGHT_Y, LIGHT_Z);
			built.put(prop.getObjectId(), model);
			return model;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build prop {}", prop.getObjectId(), e);
			return null;
		}
	}

	private ModelData merge(int[] modelIds)
	{
		if (modelIds.length == 1)
		{
			ModelData one = client.loadModelData(modelIds[0]);
			return one == null ? null : one.cloneVertices();
		}

		ModelData[] parts = new ModelData[modelIds.length];
		for (int i = 0; i < modelIds.length; i++)
		{
			parts[i] = client.loadModelData(modelIds[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		return client.mergeModels(parts).cloneVertices();
	}

	void clear()
	{
		built.clear();
	}
}
