package com.golemsdontdie;

import java.util.*;
import java.util.function.*;

/**
 * Which spaces a golem can get back from, over only what golems may use right now.
 *
 * <p>A golem goes nowhere it has no way back from. A player can take a drop into a pocket with
 * no row out, or a door golems have not yet seen opened from the far side, and a golem that
 * followed was stuck until the watchdog carried it home. So before a golem takes a transport it
 * asks whether, from where that lands, it could get back where it set off or home.
 *
 * <p>The graph is the mesh's spaces joined by every transport a golem may use (learned, or
 * worked out from the cache and shipped), by the island map's own walks between spaces, and by
 * the sea between open docks. Spaces that can each reach every other are one group: a golem
 * taking a transport from one space of a group to another can always come back. The graph is
 * built again when the table changes or what golems may use does - a level, a quest, a lever.
 */
final class WaysBack
{
	/** Ticks between asking whether what golems may use has changed. */
	static final int RECHECK_TICKS = 50;

	/** Spaces the mesh can name: its component ids are 16 bits. */
	private static final int SPACES = 1 << 16;

	/** The sea, as a space of its own that every open dock leads into and out of. Components start at 1. */
	private static final int SEA = 0;

	private final WorldMesh mesh;
	private final TransportNetwork transports;
	private final Predicate<GolemTransport> usable;

	private int builtRevision = -1;
	private int builtLinks = -1;
	private int checkedTick;
	private boolean checked;
	private BitSet builtUsable = new BitSet();
	private Set<Integer> builtPorts = Collections.emptySet();

	/** How many times the graph was built, so anything cached from it knows to forget. */
	private int builds;

	/** Per space, its group: spaces that can each reach every other. 0 for a space no edge touches. */
	private final int[] group = new int[SPACES];

	/** Per space, whether home can be reached from it. */
	private final boolean[] reachesHome = new boolean[SPACES];

	private final Map<Integer, List<Integer>> edges = new HashMap<>();
	private Map<Long, List<GolemTransport>> starting = new HashMap<>();

	/**
	 * Per row, by index: the spaces it sets off from and lands in, worked out once per build. The
	 * landing follows chains of stepping stones, which fan out hop by hop; asked afresh for every
	 * candidate on every plan it doubled the simulation's running time.
	 */
	private List<Integer>[] fromSpaces = newLists(0);
	private List<Integer>[] toSpaces = newLists(0);

	@SuppressWarnings("unchecked")
	private static List<Integer>[] newLists(int size)
	{
		return (List<Integer>[]) new List[size];
	}

	/** Whether one space reaches another, asked across groups; forgotten on each build. */
	private final Map<Long, Boolean> reaches = new HashMap<>();

	WaysBack(WorldMesh mesh, TransportNetwork transports, Predicate<GolemTransport> usable)
	{
		this.mesh = mesh;
		this.transports = transports;
		this.usable = usable;
	}

	int builds()
	{
		return builds;
	}

	/** Builds again at the next refresh, whether anything changed or not. */
	void forget()
	{
		builtRevision = -1;
		checked = false;
	}

	/** True if this row was usable when the graph was last built. */
	boolean wasUsable(GolemTransport transport)
	{
		return transport.getIndex() >= 0 && builtUsable.get(transport.getIndex());
	}

	/**
	 * Builds the graph again if the table or the joins changed, or, every so often, if what golems
	 * may use has.
	 *
	 * @param ports the spaces of every dock a golem may sail from now; none while kept ashore
	 */
	void refresh(int tick, Supplier<Set<Integer>> ports)
	{
		boolean sameTable = transports.revision() == builtRevision && mesh.linkRevision() == builtLinks;
		if (sameTable && checked && tick >= checkedTick && tick - checkedTick < RECHECK_TICKS)
		{
			return;
		}
		checked = true;
		checkedTick = tick;
		List<GolemTransport> all = transports.all();
		BitSet now = new BitSet(all.size());
		for (int i = 0; i < all.size(); i++)
		{
			GolemTransport t = all.get(i);
			if (transports.isOffered(t) && usable.test(t))
			{
				now.set(i);
			}
		}
		Set<Integer> open = ports.get();
		if (sameTable && now.equals(builtUsable) && open.equals(builtPorts))
		{
			return;
		}
		builtRevision = transports.revision();
		builtLinks = mesh.linkRevision();
		builtUsable = now;
		builtPorts = open;
		build(all);
	}

	private void build(List<GolemTransport> all)
	{
		builds++;
		reaches.clear();
		deadEnds.clear();
		edges.clear();
		fromSpaces = newLists(all.size());
		toSpaces = newLists(all.size());
		starting = new HashMap<>();
		for (int i = builtUsable.nextSetBit(0); i >= 0; i = builtUsable.nextSetBit(i + 1))
		{
			GolemTransport t = all.get(i);
			starting.computeIfAbsent(WorldMesh.tileKey(t.getFromX(), t.getFromY(), t.getFromPlane()),
				k -> new ArrayList<>()).add(t);
		}
		for (int i = builtUsable.nextSetBit(0); i >= 0; i = builtUsable.nextSetBit(i + 1))
		{
			GolemTransport t = all.get(i);
			List<Integer> to = landing(t);
			for (int from : setOff(t))
			{
				for (int space : to)
				{
					edge(from, space);
				}
			}
		}
		for (Map.Entry<Integer, List<Integer>> walked : mesh.walkedTo().entrySet())
		{
			for (int to : walked.getValue())
			{
				edge(walked.getKey(), to);
			}
		}
		for (int port : builtPorts)
		{
			edge(port, SEA);
			edge(SEA, port);
		}
		groups();
		homeward();
	}

	private void edge(int from, int to)
	{
		if (from == to)
		{
			return;
		}
		List<Integer> out = edges.computeIfAbsent(from, k -> new ArrayList<>());
		if (!out.contains(to))
		{
			out.add(to);
		}
	}

	/**
	 * The spaces a transport lands a golem in: its own end's, or following a chain of usable hops
	 * on from an end with none, a stepping stone mid-river. Empty where the mesh knows nothing.
	 */
	List<Integer> landing(GolemTransport t)
	{
		int i = t.getIndex();
		if (i >= 0 && i < toSpaces.length && toSpaces[i] != null)
		{
			return toSpaces[i];
		}
		List<Integer> spaces = mesh.spacesOnward(t.getToX(), t.getToY(), t.getToPlane(),
			t.getFromX(), t.getFromY(), t.getFromPlane(), TransportNetwork.CHAIN_HOPS, starting);
		if (i >= 0 && i < toSpaces.length)
		{
			toSpaces[i] = spaces;
		}
		return spaces;
	}

	/** The spaces a transport sets off from. */
	List<Integer> setOff(GolemTransport t)
	{
		int i = t.getIndex();
		if (i >= 0 && i < fromSpaces.length && fromSpaces[i] != null)
		{
			return fromSpaces[i];
		}
		List<Integer> spaces = mesh.spacesAt(t.getFromX(), t.getFromY(), t.getFromPlane());
		if (i >= 0 && i < fromSpaces.length)
		{
			fromSpaces[i] = spaces;
		}
		return spaces;
	}

	/**
	 * True if a golem setting off from one of these spaces and landing in one of those could come
	 * back: from where it lands it reaches where it set off, or home.
	 */
	boolean comesBack(List<Integer> from, List<Integer> to)
	{
		for (int space : to)
		{
			if (reachesHome(space))
			{
				return true;
			}
			for (int origin : from)
			{
				if (reaches(space, origin))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Spaces a pocket may run to and still be a dead end: the escape's measure of shut in. */
	static final int DEAD_END_SPACES = 8;

	/** Whether a landing is a dead end from an origin, asked before; forgotten on each build. */
	private final Map<Long, Boolean> deadEnds = new HashMap<>();

	/**
	 * True if landing in these spaces from those leads nowhere but back: everything reachable from
	 * the landing without passing back through where the golem set off is a pocket of a few spaces,
	 * no dock among them. Castle Wars' waiting rooms, a tower top, a cellar.
	 */
	boolean isDeadEnd(List<Integer> from, List<Integer> to)
	{
		if (to.isEmpty() || from.isEmpty())
		{
			return false;
		}
		// Landing where it set off - a hop within one space - is no dead end: the whole space is
		// still there. Read as one, every such hop was taken less.
		for (int space : to)
		{
			if (from.contains(space))
			{
				return false;
			}
		}
		long key = (long) to.get(0) << 16 | from.get(0);
		Boolean known = deadEnds.get(key);
		if (known != null)
		{
			return known;
		}
		int stamp = ++searches;
		for (int origin : from)
		{
			seenBy[origin] = stamp;
		}
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int pocket = 0;
		for (int space : to)
		{
			if (seenBy[space] != stamp)
			{
				seenBy[space] = stamp;
				queue.add(space);
				pocket++;
			}
		}
		boolean dead = true;
		while (!queue.isEmpty() && dead)
		{
			for (int next : edges.getOrDefault(queue.poll(), Collections.emptyList()))
			{
				if (seenBy[next] == stamp)
				{
					continue;
				}
				seenBy[next] = stamp;
				// The sea goes everywhere a boat does; more than a few spaces is somewhere else.
				if (next == SEA || ++pocket > DEAD_END_SPACES)
				{
					dead = false;
					break;
				}
				queue.add(next);
			}
		}
		if (deadEnds.size() >= MOST_REMEMBERED)
		{
			deadEnds.clear();
		}
		deadEnds.put(key, dead);
		return dead;
	}

	/** True if a golem in this space could get home. */
	boolean reachesHome(int space)
	{
		return space >= 0 && space < SPACES && reachesHome[space];
	}

	/** True if a golem in one space could get to the other. */
	boolean reaches(int from, int to)
	{
		if (from == to || group[from] != 0 && group[from] == group[to])
		{
			return true;
		}
		long key = (long) from << 16 | to;
		Boolean known = reaches.get(key);
		if (known != null)
		{
			return known;
		}
		boolean found = search(from, to);
		if (reaches.size() >= MOST_REMEMBERED)
		{
			reaches.clear();
		}
		reaches.put(key, found);
		return found;
	}

	/** Answers remembered at once before they are started afresh. */
	private static final int MOST_REMEMBERED = 100_000;

	/** Per space, the search that last saw it: one array for every search, not one each. */
	private final int[] seenBy = new int[SPACES];
	private int searches;

	private boolean search(int from, int to)
	{
		int stamp = ++searches;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		seenBy[from] = stamp;
		queue.add(from);
		while (!queue.isEmpty())
		{
			for (int next : edges.getOrDefault(queue.poll(), Collections.emptyList()))
			{
				if (next == to)
				{
					return true;
				}
				if (seenBy[next] != stamp)
				{
					seenBy[next] = stamp;
					queue.add(next);
				}
			}
		}
		return false;
	}

	/** Every space that can reach home: a search backwards along the edges from home's space. */
	private void homeward()
	{
		Arrays.fill(reachesHome, false);
		int home = mesh.componentAt(GolemContent.PLINTH_X, GolemContent.PLINTH_Y, 0);
		if (home == 0)
		{
			return;
		}
		Map<Integer, List<Integer>> back = new HashMap<>();
		for (Map.Entry<Integer, List<Integer>> e : edges.entrySet())
		{
			for (int to : e.getValue())
			{
				back.computeIfAbsent(to, k -> new ArrayList<>()).add(e.getKey());
			}
		}
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		reachesHome[home] = true;
		queue.add(home);
		while (!queue.isEmpty())
		{
			for (int prior : back.getOrDefault(queue.poll(), Collections.emptyList()))
			{
				if (!reachesHome[prior])
				{
					reachesHome[prior] = true;
					queue.add(prior);
				}
			}
		}
		// The sea is no place to stand.
		reachesHome[SEA] = false;
	}

	/**
	 * Tarjan's strongly connected components, without recursion: a chain of spaces can run to
	 * thousands, deeper than a thread's stack.
	 */
	private void groups()
	{
		Arrays.fill(group, 0);
		int[] index = new int[SPACES];
		Arrays.fill(index, -1);
		int[] low = new int[SPACES];
		boolean[] onStack = new boolean[SPACES];
		int[] stack = new int[SPACES];
		int[] callNode = new int[SPACES];
		int[] callEdge = new int[SPACES];
		int top = 0;
		int counter = 0;
		int groups = 0;
		for (int root : edges.keySet())
		{
			if (index[root] >= 0)
			{
				continue;
			}
			int depth = 0;
			callNode[0] = root;
			callEdge[0] = 0;
			index[root] = low[root] = counter++;
			stack[top++] = root;
			onStack[root] = true;
			while (depth >= 0)
			{
				int v = callNode[depth];
				List<Integer> out = edges.getOrDefault(v, Collections.emptyList());
				if (callEdge[depth] < out.size())
				{
					int w = out.get(callEdge[depth]++);
					if (index[w] < 0)
					{
						index[w] = low[w] = counter++;
						stack[top++] = w;
						onStack[w] = true;
						depth++;
						callNode[depth] = w;
						callEdge[depth] = 0;
					}
					else if (onStack[w])
					{
						low[v] = Math.min(low[v], index[w]);
					}
					continue;
				}
				if (low[v] == index[v])
				{
					groups++;
					int w;
					do
					{
						w = stack[--top];
						onStack[w] = false;
						group[w] = groups;
					}
					while (w != v);
				}
				depth--;
				if (depth >= 0)
				{
					int u = callNode[depth];
					low[u] = Math.min(low[u], low[v]);
				}
			}
		}
	}
}
