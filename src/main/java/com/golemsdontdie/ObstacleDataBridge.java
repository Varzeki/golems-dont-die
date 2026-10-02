package com.golemsdontdie;

import com.golemsdontdie.telemetry.*;
import com.google.gson.*;
import java.io.*;
import javax.inject.*;
import net.runelite.client.util.*;
import okhttp3.*;

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

	@Inject
	private GolemsDontDieConfig config;

	@Inject
	private OkHttpClient http;

	@Inject
	private Gson gson;

	private ObstacleDataFile file;
	private ObstacleDataSender sender;

	/**
	 * @param folder the plugin's own folder, or null if RuneLite could not give it one: then nothing
	 *               is kept, and play goes on without it
	 */
	void startUp(Filepath folder)
	{
		if (folder == null)
		{
			file = null;
			return;
		}
		Filepath path = folder.join(ObstacleDataFile.FILE_NAME);
		file = new ObstacleDataFile(new ObstacleDataFile.Store()
		{
			@Override
			public boolean exists()
			{
				return path.exists();
			}

			@Override
			public BufferedReader reader() throws IOException
			{
				return path.openBufferedReader();
			}

			@Override
			public void write(String text) throws IOException
			{
				folder.createDirectories();
				path.write(text);
			}
		}, GolemsDontDiePlugin.VERSION);
		file.load();
		sender = new ObstacleDataSender(http, gson, GolemsDontDiePlugin.VERSION);
	}

	/** Writes the file, and sends what is waiting if the player shares it and it is time. */
	void save()
	{
		save(false, null);
	}

	/**
	 * As {@link #save()}, sending what is waiting now rather than when it is next due: stopping.
	 *
	 * @param after run once the post is answered or given up on, or straight away if none is made
	 */
	void saveAndSend(Runnable after)
	{
		save(true, after);
	}

	private void save(boolean now, Runnable after)
	{
		if (file == null)
		{
			if (after != null)
			{
				after.run();
			}
			return;
		}
		file.save();
		sender.send(file, config.shareObstacleData(), now, after);
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
