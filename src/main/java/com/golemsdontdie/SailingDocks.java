package com.golemsdontdie;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.DBTableID;

/**
 * The game's own list of ports, read from its database at login.
 *
 * <p>Sailing ships a table of docks — sixty-odd of them, each with a name, a Sailing
 * level and sometimes a quest. Reading it live rather than hardcoding a list means the
 * plugin gains new ports when the game does and never holds a coordinate that has quietly
 * moved.
 *
 * <p>Two things here are defensive on purpose.
 *
 * <p>The first is finding the coordinate at all. The table's named columns cover the id,
 * the names, the level and the quest; the location sits in one of the unnamed ones, and
 * which one is not something to guess at and then silently get wrong. So every column is
 * probed and the one that decodes to a plausible world coordinate is used.
 *
 * <p>The second is snapping. A dock's authored position is a marker, not necessarily a
 * tile anything can float on — the Summer Shore's is a single fully blocked tile. Taken
 * literally it would make that port unreachable and every route to it fail. So each dock
 * is moved onto real open water once, at load, and the snapped tile is what gets used.
 */
@Slf4j
@Singleton
class SailingDocks
{
	/** How far to look for open water around a dock's authored position. */
	private static final int SNAP_RADIUS = 24;

	@Inject
	private Client client;

	@Inject
	private WorldMesh mesh;

	@Getter
	private final List<Dock> docks = new ArrayList<>();

	private boolean loaded;

	/** One port: where it is, and what it takes to be allowed there. */
	static final class Dock
	{
		@Getter
		private final int rowId;
		@Getter
		private final String name;
		@Getter
		private final int levelRequired;
		@Getter
		private final int questId;

		/** Water beside the dock, snapped onto the mesh where there was any to snap to. */
		@Getter
		private final WorldPoint mooring;

		/**
		 * True if this dock sits on the one connected ocean.
		 *
		 * <p>Not every dock does. Wyrmscraig has a second one inside its caves, six
		 * thousand tiles north of the island on the underground map, reached by an agility
		 * shortcut and a cave mouth — and that water is its own body, not the sea. Sailing
		 * from it is real content, but it cannot be a route across the ocean mesh, because
		 * the two are not the same water.
		 *
		 * <p>So this flag decides how a voyage is expressed rather than whether one is
		 * allowed: an open-sea dock gets a drawn route, and a dock like the cave gets a
		 * timed passage, which is how the game treats it too.
		 */
		@Getter
		private final boolean onOpenSea;

		/**
		 * The quayside: the land a golem stands on to board, and arrives on to leave.
		 *
		 * <p>The gangplank itself cannot be used for this. It is spawned with a vessel
		 * rather than built into the map — a scan of Wyrmscraig's own map squares finds
		 * the buoy and no plank — so there is no fixed plank tile to walk to. The nearest
		 * walkable land to the buoy is the same place by another name, and it exists
		 * whether or not a boat is in.
		 *
		 * <p>Boarding requires the golem to actually be here. Arriving does not: a golem
		 * coming in off the water is put ashore from wherever the crossing ended, which is
		 * how the game treats it too.
		 */
		@Getter
		private final WorldPoint shore;

		Dock(int rowId, String name, int levelRequired, int questId, WorldPoint mooring,
			boolean onOpenSea, WorldPoint shore)
		{
			this.rowId = rowId;
			this.name = name;
			this.levelRequired = levelRequired;
			this.questId = questId;
			this.mooring = mooring;
			this.onOpenSea = onOpenSea;
			this.shore = shore;
		}
	}

	/**
	 * Reads the dock table. Safe to call repeatedly; only the first call does work.
	 *
	 * <p>Must run on the client thread — it reads game state.
	 */
	void load()
	{
		if (loaded)
		{
			return;
		}

		loadBuoys();

		List<Integer> rows;
		try
		{
			rows = client.getDBTableRows(DBTableID.SailingDock.ID);
		}
		catch (RuntimeException e)
		{
			// The table is absent on a client older than Sailing. Golems simply stay on
			// land, which is the whole of the previous version's behaviour.
			log.debug("Sailing dock table unavailable", e);
			return;
		}

		if (rows == null || rows.isEmpty())
		{
			return;
		}
		loaded = true;

		for (int row : rows)
		{
			// Ask for the row's config before reading any of its fields. A row's data is
			// fetched on demand, and reading a field of a row the client has not pulled
			// yet comes back empty rather than failing — which reads exactly like a table
			// with no columns in it.
			try
			{
				client.getDBRowConfig(row);
			}
			catch (RuntimeException e)
			{
				log.debug("Dock row {} would not load", row, e);
			}

			WorldPoint authored = findCoordinate(row);
			if (authored == null)
			{
				continue;
			}
			// A dock with no ocean nearby is kept at its authored position rather than
			// dropped. Dropping it was the first version's behaviour and it silently lost
			// Wyrmscraig's cave dock — a port that exists, that golems can walk to, and
			// that simply is not on the sea.
			WorldPoint ocean = snapToWater(authored);
			WorldPoint mooring = ocean != null ? ocean : authored;

			docks.add(new Dock(row,
				string(row, DBTableID.SailingDock.COL_NICE_NAME),
				integer(row, DBTableID.SailingDock.COL_LEVEL_REQUIRED, 1),
				integer(row, DBTableID.SailingDock.COL_QUEST_REQUIRED, -1),
				mooring, ocean != null, quayside(integer(row, DBTableID.SailingDock.COL_DOCK_ID, -1), authored)));
		}

		// A quayside on an island the land fill never reached is land from now on, or a golem
		// that sails there has nowhere to step off.
		for (Dock dock : docks)
		{
			WorldPoint shore = dock.getShore();
			mesh.admitDockFloor(shore.getX(), shore.getY(), shore.getPlane());
		}

		log.debug("Loaded {} sailing docks of {} rows; {} open to golems at Sailing level {}", docks.size(),
			rows.size(), openDocks().size(), client.getRealSkillLevel(Skill.SAILING));

		if (docks.isEmpty() && !rows.isEmpty())
		{
			// The table is there and every row was rejected, which means the shape of it
			// is not what this code expects. That is worth saying loudly and in detail:
			// silently having no sailing, with a healthy-looking row count in the log, is
			// the kind of failure that takes an afternoon to find.
			dumpTable(rows);
		}
	}

	/**
	 * Dumps what the dock table actually contains, when nothing could be read from it.
	 *
	 * <p>The location is in one of the table's unnamed columns — the client API names the
	 * id, the names, the level and the quest, and nothing else — so this code has to find
	 * it by inspection. If the packing or the column set ever changes, this is what turns
	 * "no golems sail any more" into a five-line answer.
	 */
	private void dumpTable(List<Integer> rows)
	{
		log.warn("No sailing dock resolved a location. Dumping the first rows of table {}:",
			DBTableID.SailingDock.ID);

		for (int i = 0; i < Math.min(3, rows.size()); i++)
		{
			int row = rows.get(i);
			StringBuilder sb = new StringBuilder("  row ").append(row).append(':');

			try
			{
				sb.append(" config=").append(client.getDBRowConfig(row));
			}
			catch (RuntimeException e)
			{
				sb.append(" config threw ").append(e);
			}

			for (int column = 0; column < 24; column++)
			{
				Object[] values;
				try
				{
					values = client.getDBTableField(row, column, 0);
				}
				catch (RuntimeException e)
				{
					sb.append("\n    [").append(column).append("] threw ").append(e);
					continue;
				}
				if (values == null)
				{
					sb.append("\n    [").append(column).append("] null");
					continue;
				}
				if (values.length == 0)
				{
					sb.append("\n    [").append(column).append("] empty");
					continue;
				}
				sb.append("\n    [").append(column).append("] ");
				for (Object value : values)
				{
					sb.append(value == null ? "null" : value.getClass().getSimpleName()
						+ "=" + value).append(' ');
				}
			}
			log.warn(sb.toString());
		}
	}

	/**
	 * True if a golem is allowed to sail to this dock.
	 *
	 * <p>The player's real Sailing level and the dock's quest, both applied. A golem
	 * inherits what the player has unlocked — it is their game — but never a boost, since
	 * a golem cannot drink a potion and a port that opened only while the player happened
	 * to be boosted would strand golems there afterwards.
	 */
	boolean isOpen(Dock dock)
	{
		if (client.getRealSkillLevel(Skill.SAILING) < dock.getLevelRequired())
		{
			return false;
		}
		if (dock.getQuestId() <= 0)
		{
			return true;
		}
		for (Quest quest : Quest.values())
		{
			if (quest.getId() == dock.getQuestId())
			{
				return quest.getState(client) == QuestState.FINISHED;
			}
		}
		return true;
	}

	/** Every dock currently open to a golem. */
	List<Dock> openDocks()
	{
		List<Dock> out = new ArrayList<>();
		for (Dock dock : docks)
		{
			if (isOpen(dock))
			{
				out.add(dock);
			}
		}
		return out;
	}

	Dock byRow(int rowId)
	{
		for (Dock dock : docks)
		{
			if (dock.getRowId() == rowId)
			{
				return dock;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- reading

	/**
	 * Where a dock is, from the shipped table of docking buoys.
	 *
	 * <p>The dock table has no location in it. It carries a dock id, two names, a Sailing
	 * level and a sprite, and that is all — a dock's position in the world is the position
	 * of its <em>docking buoy</em>, an ordinary scene object with a {@code Dock} action.
	 * Those were harvested by {@code dev-tools/BuildDocks.java} and shipped.
	 *
	 * <p>The link between the two is {@link DBTableID.SailingDock#COL_DOCK_ID}, which is
	 * the index into that list in object-id order. Verified against Wyrmscraig, whose row
	 * reads dock id 59 against the 60th buoy, and its cavern, 60 against the 61st.
	 */
	private WorldPoint findCoordinate(int row)
	{
		int dockId = integer(row, DBTableID.SailingDock.COL_DOCK_ID, -1);
		if (dockId < 0 || dockId >= buoys.size())
		{
			return null;
		}
		return buoys.get(dockId);
	}

	/** Docking buoy positions, indexed by dock id. Read once from the jar. */
	private final List<WorldPoint> buoys = new ArrayList<>();

	/**
	 * Gangplank positions, indexed the same way; the buoy's own tile where none was found.
	 *
	 * <p>The plank is where a person walks aboard, so it is a far better quayside than the
	 * nearest land to the buoy — which can be across the water, or the wrong pier.
	 */
	private final List<WorldPoint> gangplanks = new ArrayList<>();

	/**
	 * Reads the shipped table: a count, then six shorts a dock.
	 *
	 * <p>The layout is {@code BuildDocks}': the buoy's tile, then the gangplank's, each as
	 * x, y and plane. This read {@code int, short, short, byte} instead — four bytes skipped,
	 * then three fields taken across the boundaries of the next ones — so every dock in the
	 * game came out somewhere like 0,3038 on plane 12. Nothing snapped to water, no quayside
	 * had land beside it, and no golem could board anywhere: sailing has never once happened.
	 */
	private void loadBuoys()
	{
		if (!buoys.isEmpty())
		{
			return;
		}

		try (java.io.InputStream raw = SailingDocks.class.getResourceAsStream(BUOYS))
		{
			if (raw == null)
			{
				log.warn("Docking buoy table missing from the jar; golems cannot sail");
				return;
			}
			try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw);
				 java.io.DataInputStream data = new java.io.DataInputStream(gz))
			{
				int count = data.readInt();
				for (int i = 0; i < count; i++)
				{
					int buoyX = data.readShort() & 0xFFFF;
					int buoyY = data.readShort() & 0xFFFF;
					int buoyPlane = data.readShort() & 0xFFFF;
					int plankX = data.readShort() & 0xFFFF;
					int plankY = data.readShort() & 0xFFFF;
					int plankPlane = data.readShort() & 0xFFFF;
					buoys.add(new WorldPoint(buoyX, buoyY, buoyPlane));
					gangplanks.add(new WorldPoint(plankX, plankY, plankPlane));
				}
				log.debug("Loaded {} docking buoys", buoys.size());
			}
		}
		catch (java.io.IOException | RuntimeException e)
		{
			log.warn("Docking buoy table unreadable", e);
			buoys.clear();
			gangplanks.clear();
		}
	}

	/**
	 * Where a golem boards this dock: the gangplank if the harvest found one and a golem can
	 * stand on it, otherwise the nearest land to the buoy.
	 *
	 * <p>A plank equal to its buoy means no plank was found within range — several docks,
	 * Wyrmscraig's among them, are like that.
	 */
	private WorldPoint quayside(int dockId, WorldPoint buoy)
	{
		WorldPoint plank = dockId < gangplanks.size() ? gangplanks.get(dockId) : null;
		if (plank != null && !plank.equals(buoy)
			&& mesh.isLandWalkable(plank.getX(), plank.getY(), plank.getPlane()))
		{
			return plank;
		}
		return snapToShore(buoy);
	}

	private static final String BUOYS = "/docks.gz";

	/**
	 * The nearest land a golem could stand on, spiralling out from the buoy.
	 *
	 * <p>A buoy sits on water, so this is what turns a dock into somewhere a walking golem
	 * can be. Falls back to the buoy itself if there is no land nearby, which leaves the
	 * port usable by sea even where the mesh has nothing ashore.
	 */
	private WorldPoint snapToShore(WorldPoint at)
	{
		for (int radius = 0; radius <= SNAP_RADIUS; radius++)
		{
			WorldPoint pier = null;
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (radius > 0 && Math.abs(dx) != radius && Math.abs(dy) != radius)
					{
						continue;
					}
					int x = at.getX() + dx;
					int y = at.getY() + dy;
					if (mesh.isLandWalkable(x, y, at.getPlane())
						&& !mesh.isIsolated(x, y, at.getPlane()))
					{
						return new WorldPoint(x, y, at.getPlane());
					}
					// Ground the land fill never reached is still ground. Most of Sailing's
					// islands are like that — no transport table leads onto them — and a dock
					// is itself the proof a player stands there. Taken only if nothing the
					// fill knows is as close.
					if (pier == null && mesh.isWalkable(x, y, at.getPlane())
						&& !mesh.isOcean(x, y, at.getPlane()) && mesh.componentAt(x, y, at.getPlane()) != 0)
					{
						pier = new WorldPoint(x, y, at.getPlane());
					}
				}
			}
			if (pier != null)
			{
				return pier;
			}
		}
		return at;
	}

	/** The nearest tile of the connected ocean, spiralling outward. */
	private WorldPoint snapToWater(WorldPoint at)
	{
		if (mesh.isOcean(at.getX(), at.getY(), at.getPlane()))
		{
			return at;
		}
		for (int radius = 1; radius <= SNAP_RADIUS; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.abs(dx) != radius && Math.abs(dy) != radius)
					{
						continue;
					}
					int x = at.getX() + dx;
					int y = at.getY() + dy;
					if (mesh.isOcean(x, y, 0))
					{
						return new WorldPoint(x, y, 0);
					}
				}
			}
		}
		return null;
	}

	private String string(int row, int column)
	{
		try
		{
			Object[] values = client.getDBTableField(row, column, 0);
			return values != null && values.length > 0 && values[0] != null
				? String.valueOf(values[0]) : "";
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	private int integer(int row, int column, int fallback)
	{
		try
		{
			Object[] values = client.getDBTableField(row, column, 0);
			return values != null && values.length > 0 && values[0] instanceof Integer
				? (Integer) values[0] : fallback;
		}
		catch (RuntimeException e)
		{
			return fallback;
		}
	}
}
