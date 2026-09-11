import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;

/**
 * Harvests the scenery a golem interacts with, so the object can be animated too.
 *
 * <p>The client's {@code ObjectComposition} exposes an object's name, its actions and its
 * size, but <b>not its model ids</b> — where {@code NPCComposition} does. So an object's
 * geometry cannot be reached at runtime at all, and the only way to draw a copy of a
 * gangplank is to have read its model ids out of the cache beforehand. That is what this
 * does.
 *
 * <p>It also takes the object's own {@code animationID}, which is better than hardcoding a
 * gameval: it is whatever that specific plank, gate or ring actually plays, so a door and a
 * portcullis each get their own motion without anyone having to enumerate them.
 *
 * <p>Only objects that transports actually reference are kept — a few hundred out of forty
 * thousand — and only those with an animation, since an object that does not move has
 * nothing to contribute and drawing a static copy on top of the real one would do nothing
 * but cost a draw call.
 *
 * <pre>
 * javac -cp cache.jar -d out dev-tools/BuildProps.java
 * java  -cp "cache.jar;deps.jar;out" BuildProps &lt;transports-dir&gt; &lt;cache-dir&gt; [out.gz]
 * </pre>
 */
public class BuildProps
{
	public static void main(String[] args) throws IOException
	{
		if (args.length < 2)
		{
			System.err.println("usage: BuildProps <transports-dir> <cache-dir> [out.gz]");
			System.exit(1);
		}

		Set<Integer> wanted = objectIdsUsedByTransports(new File(args[0]));
		if (args.length > 3)
		{
			wanted.addAll(objectIdsUsedByTransports(new File(args[3])));
		}
		System.out.println("object ids referenced by transports: " + wanted.size());

		Map<Integer, Prop> props = new TreeMap<>();
		try (Store store = new Store(new File(args[1])))
		{
			store.load();
			ObjectManager objects = new ObjectManager(store);
			objects.load();

			for (ObjectDefinition def : objects.getObjects())
			{
				if (!wanted.contains(def.getId()))
				{
					continue;
				}
				int animation = def.getAnimationID();
				int[] models = def.getObjectModels();
				if (animation <= 0 || models == null || models.length == 0)
				{
					// No animation means nothing to draw that the real object is not
					// already doing.
					continue;
				}

				Prop prop = new Prop();
				prop.objectId = def.getId();
				prop.name = def.getName() == null ? "" : def.getName();
				prop.animation = animation;
				prop.models = models;
				prop.ambient = def.getAmbient();
				prop.contrast = def.getContrast();
				prop.sizeX = def.getSizeX();
				prop.sizeY = def.getSizeY();
				props.put(prop.objectId, prop);
			}
		}

		System.out.println("animated props: " + props.size());
		System.out.println();
		System.out.println("  object  anim    models                name");
		int shown = 0;
		for (Prop p : props.values())
		{
			if (shown++ >= 25)
			{
				System.out.println("  ... and " + (props.size() - 25) + " more");
				break;
			}
			System.out.println("  " + pad(p.objectId, 8) + pad(p.animation, 8)
				+ pad(Arrays.toString(p.models), 22) + p.name);
		}

		if (args.length > 2)
		{
			write(new File(args[2]), props.values());
		}
	}

	private static Set<Integer> objectIdsUsedByTransports(File dir) throws IOException
	{
		Set<Integer> out = new TreeSet<>();
		File[] files = dir.listFiles((d, n) -> n.endsWith(".tsv"));
		if (files == null)
		{
			return out;
		}

		for (File f : files)
		{
			try (BufferedReader r = new BufferedReader(new FileReader(f)))
			{
				String[] header = null;
				String line;
				while ((line = r.readLine()) != null)
				{
					if (line.trim().isEmpty())
					{
						continue;
					}
					if (line.startsWith("#"))
					{
						if (header == null)
						{
							header = line.substring(1).trim().split("\t");
						}
						continue;
					}
					if (header == null)
					{
						header = line.split("\t");
						continue;
					}

					String menu = col(header, line.split("\t", -1),
						"menuOption menuTarget objectID");
					if (menu.isEmpty())
					{
						continue;
					}
					String[] parts = menu.trim().split("\\s+");
					try
					{
						out.add(Integer.parseInt(parts[parts.length - 1]));
					}
					catch (NumberFormatException ignored)
					{
						// A menu string with no trailing id. Nothing to harvest.
					}
				}
			}
		}
		return out;
	}

	private static void write(File out, Iterable<Prop> props) throws IOException
	{
		List<Prop> list = new ArrayList<>();
		props.forEach(list::add);

		try (DataOutputStream d = new DataOutputStream(
			new GZIPOutputStream(new FileOutputStream(out))))
		{
			d.writeInt(list.size());
			for (Prop p : list)
			{
				d.writeInt(p.objectId);
				d.writeInt(p.animation);
				d.writeShort(p.ambient);
				d.writeShort(p.contrast);
				d.writeByte(p.sizeX);
				d.writeByte(p.sizeY);
				d.writeByte(p.models.length);
				for (int model : p.models)
				{
					d.writeInt(model);
				}
			}
		}
		System.out.println();
		System.out.println("Wrote " + out + " (" + out.length() + " bytes, "
			+ list.size() + " props)");
	}

	private static String col(String[] header, String[] fields, String name)
	{
		for (int i = 0; i < header.length; i++)
		{
			if (header[i].trim().equalsIgnoreCase(name) && i < fields.length)
			{
				return fields[i].trim();
			}
		}
		return "";
	}

	private static String pad(Object value, int width)
	{
		StringBuilder sb = new StringBuilder(String.valueOf(value));
		while (sb.length() < width)
		{
			sb.append(' ');
		}
		return sb.toString();
	}

	private static final class Prop
	{
		int objectId;
		int animation;
		int ambient;
		int contrast;
		int sizeX;
		int sizeY;
		int[] models;
		String name = "";
	}
}
