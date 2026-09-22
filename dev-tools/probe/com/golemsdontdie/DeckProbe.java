package com.golemsdontdie;

/**
 * Where a crew stands, drawn out: every berth of every boat against the hull it is meant to be on,
 * and the same berths turned to each compass point to check the sums that put them there.
 *
 * <p>Crews cannot be tried out without a client — docks come from the game's own tables — so this
 * is what stands in for it: a berth off the end of the deck, or a crew that swings round the boat
 * instead of riding on it, shows up here.
 *
 * <pre>
 * java -cp "probe;classes;resources;slf4j;api" com.golemsdontdie.DeckProbe
 * </pre>
 */
public class DeckProbe
{
	/** How wide the plan is drawn, in characters, across the widest hull. */
	private static final int COLUMNS = 21;

	public static void main(String[] args)
	{
		for (GolemBoat boat : GolemBoat.values())
		{
			plan(boat);
		}
		turning();
	}

	/** A plan view of the boat, bow at the top, with each berth where it falls. */
	private static void plan(GolemBoat boat)
	{
		int width = boat.getHullMaxX() - boat.getHullMinX();
		int length = boat.getHullMaxZ() - boat.getHullMinZ();
		int rows = Math.max(8, Math.round(length / 96f));
		char[][] grid = new char[rows][COLUMNS];
		for (char[] row : grid)
		{
			java.util.Arrays.fill(row, '.');
		}

		int off = 0;
		for (int berth = 0; berth < boat.getBerths(); berth++)
		{
			int[] at = boat.berthInHull(berth);
			boolean aboard = at[0] >= boat.getHullMinX() && at[0] <= boat.getHullMaxX()
				&& at[1] >= boat.getHullMinZ() && at[1] <= boat.getHullMaxZ();
			off += aboard ? 0 : 1;
			// Bow is -z and is drawn at the top; across grows to starboard, drawn rightwards.
			int row = (int) ((at[1] - boat.getHullMinZ()) / (float) length * (rows - 1));
			int column = (int) ((at[0] - boat.getHullMinX()) / (float) width * (COLUMNS - 1));
			row = Math.max(0, Math.min(rows - 1, row));
			column = Math.max(0, Math.min(COLUMNS - 1, column));
			grid[row][column] = berth == 0 ? 'H' : (char) ('1' + berth - 1);
			if (!aboard)
			{
				System.out.printf("  berth %d is off the deck at %d,%d%n", berth, at[0], at[1]);
			}
		}

		System.out.printf("%s: %dx%d model units, %d berths, helm at %d, %d off the deck%n",
			boat, width, length, boat.getBerths(), boat.getHelmOffset(), off);
		System.out.println("   bow");
		for (char[] row : grid)
		{
			System.out.println("   |" + new String(row) + "|");
		}
		System.out.println("   stern (H helm, 1.. crew; port left, starboard right)");
		System.out.println();
	}

	/** The same berth at four headings: it should ride round with the boat, never away from it. */
	private static void turning()
	{
		System.out.println("A sloop's bow berth, turned:");
		GolemBoat boat = GolemBoat.SLOOP;
		int[] slot = boat.getDeck()[boat.getBerths() - 1];
		for (int orientation : new int[]{0, 512, 1024, 1536})
		{
			double facing = orientation * Math.PI / 1024;
			int dx = (int) Math.round(slot[1] * -Math.sin(facing) + slot[0] * -Math.cos(facing));
			int dy = (int) Math.round(slot[1] * -Math.cos(facing) + slot[0] * Math.sin(facing));
			String way = orientation == 0 ? "south" : orientation == 512 ? "west"
				: orientation == 1024 ? "north" : "east";
			System.out.printf("  facing %-5s (%4d): %+5d,%+5d from the helm%n", way, orientation, dx, dy);
		}
	}
}
