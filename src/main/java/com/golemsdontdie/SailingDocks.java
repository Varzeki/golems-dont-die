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
 * <p>Sailing ships a table of docks — sixty-odd, with a name, a level and sometimes a quest.
 * Reading it live means the plugin gains new ports when the game does.
 *
 * <p>Two things are defensive on purpose. The location sits in one of the table's unnamed
 * columns, so every column is probed and the one decoding to a plausible world coordinate wins.
 * And a dock's authored position is a marker, not necessarily a tile anything can float on — the
 * Summer Shore's is one fully blocked tile — so each dock is snapped onto open water at load.
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
		 * True if this dock sits on the one connected ocean. Not every dock does: Wyrmscraig has a
		 * second inside its caves, whose water is its own body. The flag decides how a voyage is
		 * expressed — an open-sea dock gets a drawn route, a cave dock a timed passage.
		 */
		@Getter
		private final boolean onOpenSea;

		/**
		 * The quayside: the land a golem stands on to board, and arrives on to leave. The gangplank is
		 * spawned with a vessel rather than built into the map, so there is no fixed plank tile to
		 * walk to. Boarding requires the golem to be here; arriving does not.
		 */
		@Getter
		private final WorldPoint shore;

		/**
		 * The open water nearest the gangplank: where a raft sets out from and comes in to. Not the
		 * buoy, which can sit well out in the harbour. Sea routes still run from the mooring.
		 */
		@Getter
		private final WorldPoint berth;

		Dock(int rowId, String name, int levelRequired, int questId, WorldPoint mooring,
			boolean onOpenSea, WorldPoint shore)
		{
			this(rowId, name, levelRequired, questId, mooring, onOpenSea, shore, mooring);
		}

		Dock(int rowId, String name, int levelRequired, int questId, WorldPoint mooring,
			boolean onOpenSea, WorldPoint shore, WorldPoint berth)
		{
			this.rowId = rowId;
			this.name = name;
			this.levelRequired = levelRequired;
			this.questId = questId;
			this.mooring = mooring;
			this.onOpenSea = onOpenSea;
			this.shore = shore;
			this.berth = berth;
		}
	}

	/** Reads the dock table, once, on the client thread. Safe to call repeatedly. */
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
			// Absent on a client older than Sailing; golems then simply stay on land.
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
			// Ask for the row's config before reading any of its fields: a row's data is fetched on
			// demand, and a field of a row the client has not pulled comes back empty rather than
			// failing, which reads exactly like a table with no columns in it.
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
			// A dock with no ocean nearby is kept at its authored position rather than dropped:
			// dropping it silently lost Wyrmscraig's cave dock, a port that simply is not on the sea.
			WorldPoint ocean = snapToWater(authored);
			WorldPoint mooring = ocean != null ? ocean : authored;

			WorldPoint shore = quayside(integer(row, DBTableID.SailingDock.COL_DOCK_ID, -1), authored, ocean != null);
			// On the sea, the sea nearest the gangplank. In a cave, the cave's own water beside it.
			WorldPoint berth = ocean != null ? snapToWater(shore) : snapToInlandWater(shore);
			docks.add(new Dock(row,
				string(row, DBTableID.SailingDock.COL_NICE_NAME),
				integer(row, DBTableID.SailingDock.COL_LEVEL_REQUIRED, 1),
				integer(row, DBTableID.SailingDock.COL_QUEST_REQUIRED, -1),
				mooring, ocean != null, shore, berth != null ? berth : mooring));
		}

		// A quayside on an island the land fill never reached is land from now on, or a golem that
		// sails there has nowhere to step off.
		for (Dock dock : docks)
		{
			// Only docks on the sea: a cave dock stands on ground the fill knows, and admitting more
			// there admitted the cave's lake.
			if (!dock.isOnOpenSea())
			{
				continue;
			}
			WorldPoint shore = dock.getShore();
			mesh.admitDockFloor(shore.getX(), shore.getY(), shore.getPlane());
		}

		log.debug("Loaded {} sailing docks of {} rows; {} open to golems at Sailing level {}", docks.size(),
			rows.size(), openDocks().size(), client.getRealSkillLevel(Skill.SAILING));

		if (docks.isEmpty() && !rows.isEmpty())
		{
			// Every row rejected means the table's shape is not what this code expects, which is
			// worth saying loudly: silent no-sailing with a healthy row count takes an afternoon.
			dumpTable(rows);
		}
	}

	/**
	 * Dumps what the dock table actually contains, when nothing could be read from it. The location
	 * is in an unnamed column and found by inspection, so if the packing ever changes this turns
	 * "no golems sail" into a five-line answer.
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
	 * True if a golem is allowed to sail to this dock: the player's real Sailing level and the dock's
	 * quest, both applied. Never a boost — a port that opened only while boosted would strand golems.
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
		Quest quest = QUESTS_BY_ID.get(dock.getQuestId());
		return quest == null || questFinished(quest);
	}

	/** How long a quest's state is trusted. Asking runs a client script, once per dock considered. */
	private static final long QUEST_RECHECK_MILLIS = 5 * 60 * 1000L;

	/** Quest id to {finished ? 1 : 0, when it was asked}. */
	private final java.util.Map<Integer, long[]> questStates = new java.util.HashMap<>();

	private boolean questFinished(Quest quest)
	{
		long now = System.currentTimeMillis();
		long[] known = questStates.get(quest.getId());
		if (known == null || now - known[1] >= QUEST_RECHECK_MILLIS)
		{
			known = new long[]{quest.getState(client) == QuestState.FINISHED ? 1 : 0, now};
			questStates.put(quest.getId(), known);
		}
		return known[0] == 1;
	}

	/** Forgets every quest state, for a login that may be another account. */
	void forgetQuests()
	{
		questStates.clear();
	}

	/** Quests by id. Quest.values() copies the whole list each call, once per dock considered. */
	private static final java.util.Map<Integer, Quest> QUESTS_BY_ID = new java.util.HashMap<>();

	static
	{
		for (Quest quest : Quest.values())
		{
			QUESTS_BY_ID.putIfAbsent(quest.getId(), quest);
		}
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
	 * <p>The dock table has no location in it — a dock's position is that of its <em>docking
	 * buoy</em>, a scene object with a {@code Dock} action, read offline from the cache. The
	 * link is {@link DBTableID.SailingDock#COL_DOCK_ID}, the index into that list in object-id
	 * order: Wyrmscraig's row reads dock id 59, the 60th buoy.
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
	 * Gangplank positions, indexed the same way; the buoy's own tile where none was found. The plank
	 * is where a person walks aboard, so it beats the nearest land to the buoy — across the water,
	 * or the wrong pier.
	 */
	private final List<WorldPoint> gangplanks = new ArrayList<>();

	/**
	 * Reads the shipped table: a count, then six shorts a dock — the buoy's tile then the
	 * gangplank's, each x, y and plane, in the order they were written. Reading
	 * {@code int, short, short, byte} put every dock at something like 0,3038 on plane 12.
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
	 * Where a golem boards this dock: the gangplank if the harvest found one and a golem can stand on
	 * it, else the nearest land to the buoy. A plank equal to its buoy means none was found in range.
	 */
	private WorldPoint quayside(int dockId, WorldPoint buoy)
	{
		return quayside(dockId, buoy, true);
	}

	/**
	 * @param onOpenSea false for a dock on water that is not the sea — Wyrmscraig's cave, where
	 *                  every open tile near the buoy is the lake, passable but not ocean, so it was
	 *                  chosen as the quayside, admitted as floor, and golems walked on the water.
	 */
	private WorldPoint quayside(int dockId, WorldPoint buoy, boolean onOpenSea)
	{
		WorldPoint plank = dockId < gangplanks.size() ? gangplanks.get(dockId) : null;
		if (plank != null && !plank.equals(buoy)
			&& mesh.isLandWalkable(plank.getX(), plank.getY(), plank.getPlane()))
		{
			return plank;
		}
		return snapToShore(buoy, onOpenSea);
	}

	private static final String BUOYS = "/docks.gz";

	/**
	 * The nearest land a golem could stand on, spiralling out from the buoy, which sits on water.
	 * Falls back to the buoy, leaving the port usable by sea where the mesh has nothing ashore.
	 */
	private WorldPoint snapToShore(WorldPoint at)
	{
		return snapToShore(at, true);
	}

	/** @param piers false to take only ground the land fill reached. See quayside. */
	private WorldPoint snapToShore(WorldPoint at, boolean piers)
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
					// Ground the land fill never reached is still ground: most of Sailing's
					// islands are like that. Taken only if nothing the fill knows is as close.
					if (piers && pier == null && mesh.isWalkable(x, y, at.getPlane())
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

	/**
	 * The nearest water that is not the sea: open in the map, not land the fill reached, part of some
	 * connected space. Around the cave dock that is its lake.
	 */
	private WorldPoint snapToInlandWater(WorldPoint at)
	{
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
					if (mesh.isWalkable(x, y, at.getPlane()) && !mesh.isOcean(x, y, at.getPlane())
						&& !mesh.isLandWalkable(x, y, at.getPlane()) && mesh.componentAt(x, y, at.getPlane()) != 0)
					{
						return new WorldPoint(x, y, at.getPlane());
					}
				}
			}
		}
		return null;
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
