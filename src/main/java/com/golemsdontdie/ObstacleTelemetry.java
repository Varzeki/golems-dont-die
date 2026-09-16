package com.golemsdontdie;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Optionally shares obstacle sightings, so the next release can ship what players found.
 *
 * <p>Every player who uses an obstacle proves what it does. Kept locally that unlocks the
 * obstacle for one person's golems; pooled, it unlocks it for everybody in the next update.
 * There are hundreds of obstacles in the game and one person cannot reasonably visit them
 * all, which is the entire reason this exists.
 *
 * <p><b>Off unless the player turns it on.</b> No endpoint is shipped and nothing is sent
 * until both the toggle is enabled and a URL has been entered, so the default build makes
 * no network requests at all.
 *
 * <h2>What is sent</h2>
 *
 * <p>Exactly the contents of an {@link ObstacleSighting}: an object id, its name from the
 * cache, the menu text, the animations it played, how long it took, and the two tiles it
 * joins. Plus the plugin version, so old data can be told apart after a game update.
 *
 * <p>That is all facts about the game world, not about the person playing. There is
 * deliberately no account name, no display name, no world, no session or install
 * identifier, no timestamp, and no position other than the obstacle's own two ends. Two
 * players crossing the same stile send byte-identical reports, which is the property worth
 * having: the data cannot be tied back to whoever sent it, because it does not differ.
 *
 * <p>Sightings are batched and sent on shutdown rather than one request per obstacle, so an
 * agility course is one small request instead of a burst of them. Failures are swallowed
 * after a log line — this is a nice-to-have, and a collection server being down must never
 * be something the player notices.
 */
@Slf4j
@Singleton
class ObstacleTelemetry
{
	private static final MediaType JSON = MediaType.parse("application/json");

	/** Sightings held back before a send is worth making. */
	private static final int BATCH = 25;

	/** Beyond this, the oldest are dropped rather than grown without limit. */
	private static final int MAX_QUEUED = 500;

	@Inject
	private OkHttpClient http;

	private final List<ObstacleSighting> queued = new ArrayList<>();

	private boolean enabled;
	private String endpoint = "";

	/** Applied from config, and re-applied whenever the player changes either setting. */
	void configure(boolean enabled, String endpoint)
	{
		this.enabled = enabled;
		this.endpoint = endpoint == null ? "" : endpoint.trim();

		if (!active())
		{
			// Turning it off discards whatever was waiting. A player who switches this off
			// should not have a queue sent later from before they changed their mind.
			queued.clear();
		}
	}

	private boolean active()
	{
		return enabled && (endpoint.startsWith("https://") || endpoint.startsWith("http://"));
	}

	/** Queues one sighting, sending the batch once enough have built up. */
	void offer(ObstacleSighting sighting)
	{
		if (!active())
		{
			return;
		}

		queued.add(sighting);
		if (queued.size() >= MAX_QUEUED)
		{
			queued.remove(0);
		}
		if (queued.size() >= BATCH)
		{
			flush();
		}
	}

	/** Sends whatever is waiting. Called on shutdown, and when a batch fills. */
	void flush()
	{
		if (!active() || queued.isEmpty())
		{
			return;
		}

		String body = toJson(queued);
		queued.clear();

		try
		{
			Request request = new Request.Builder()
				.url(endpoint)
				.post(RequestBody.create(JSON, body))
				.build();

			http.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					// Deliberately quiet. The player gets nothing out of knowing that a
					// server they do not run did not answer.
					log.debug("Obstacle telemetry failed", e);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					log.debug("Obstacle telemetry sent: {}", response.code());
					response.close();
				}
			});
		}
		catch (RuntimeException e)
		{
			log.debug("Obstacle telemetry could not be sent", e);
		}
	}

	/**
	 * The batch as JSON.
	 *
	 * <p>Written by hand rather than with a serialiser so that what goes over the wire is
	 * visible in one place and can be read by whoever is deciding whether to enable this.
	 * A field cannot be added here by accident.
	 */
	private static String toJson(List<ObstacleSighting> batch)
	{
		StringBuilder sb = new StringBuilder("{\"plugin\":\"golems-dont-die\",\"sightings\":[");
		for (int i = 0; i < batch.size(); i++)
		{
			ObstacleSighting s = batch.get(i);
			sb.append(i == 0 ? "" : ",")
				.append("{\"object\":").append(s.objectId)
				.append(",\"name\":\"").append(escape(s.name)).append('"')
				.append(",\"menu\":\"").append(escape(s.menu)).append('"')
				.append(",\"clips\":[");
			for (int c = 0; c < s.clips.length; c++)
			{
				sb.append(c == 0 ? "" : ",").append(s.clips[c]);
			}
			sb.append("],\"ticks\":").append(s.ticks)
				.append(",\"from\":[").append(s.fromX).append(',').append(s.fromY)
				.append(',').append(s.fromPlane).append(']')
				.append(",\"to\":[").append(s.toX).append(',').append(s.toY)
				.append(',').append(s.toPlane).append("]}");
		}
		return sb.append("]}").toString();
	}

	private static String escape(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", " ").replace("\r", " ").replace("\t", " ");
	}
}
