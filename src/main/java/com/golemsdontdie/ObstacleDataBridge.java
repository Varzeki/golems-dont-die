package com.golemsdontdie;

import com.golemsdontdie.telemetry.GolemCrossing;
import com.golemsdontdie.telemetry.ObstacleDataFile;
import com.golemsdontdie.telemetry.PlayerCrossing;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/**
 * The one place the plugin hands anything to the obstacle data file.
 *
 * <p>Copies numbers out of the plugin's own objects into the plain records of the
 * {@code telemetry} package, and nothing else: no decisions about what to keep are made here beyond
 * which fields to copy. See that package for what is kept and why.
 */
@Singleton
class ObstacleDataBridge
{
	@Inject
	private TransportNetwork transports;

	private ObstacleDataFile file;

	void startUp()
	{
		file = new ObstacleDataFile(RuneLite.RUNELITE_DIR, GolemsDontDiePlugin.VERSION);
		file.load();
	}

	void save()
	{
		if (file != null)
		{
			file.save();
		}
	}

	/** A crossing the player made, already judged to be an obstacle. */
	void playerCrossed(ObstacleSighting s)
	{
		if (file == null)
		{
			return;
		}
		MotionCurve curve = s.curve;
		file.add(new PlayerCrossing(s.objectId, s.name, s.menu, s.clips, s.ticks, s.moveDelay, s.moveSpan,
			s.fromX, s.fromY, s.fromPlane, s.toX, s.toY, s.toPlane, s.lineX, s.lineY, s.instance,
			curve == null ? new int[0][] : curve.samples(),
			curve == null ? new int[0][] : curve.animationChanges(),
			curve == null ? -1 : curve.facing()));
	}

	/** A crossing a golem made in view. See GolemTraversal. */
	void golemCrossed(GolemTraversal t)
	{
		if (file == null)
		{
			return;
		}
		GolemTransport route = t.route;
		file.add(new GolemCrossing(route.getObjectId(),
			route.getFromX(), route.getFromY(), route.getFromPlane(),
			route.getToX(), route.getToY(), route.getToPlane(),
			t.samples.toArray(new int[0][]), passesOverStone(route)));
	}

	/**
	 * True if a route passes over a tile another short hop starts on, between its two ends: a
	 * stepping stone the golem did not land on.
	 */
	private boolean passesOverStone(GolemTransport route)
	{
		int dx = Integer.signum(route.getToX() - route.getFromX());
		int dy = Integer.signum(route.getToY() - route.getFromY());
		int x = route.getFromX() + dx;
		int y = route.getFromY() + dy;
		for (int step = 0; step < 16 && (x != route.getToX() || y != route.getToY()); step++)
		{
			if (transports.isStone(x, y, route.getFromPlane()))
			{
				return true;
			}
			x += dx;
			y += dy;
		}
		return false;
	}
}
