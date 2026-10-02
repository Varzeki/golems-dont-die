package com.golemsdontdie.telemetry;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import lombok.extern.slf4j.*;
import okhttp3.*;

/**
 * Sends the obstacle data file, for a player who has turned on sharing. The only network code in the
 * plugin.
 *
 * <p>In the background, on RuneLite's own HTTP client: a batch of what the file has not sent is taken
 * on the client thread and posted without waiting for it. Only once the server takes it is it marked
 * sent; a post that fails leaves it to go with the next. One the server refused as malformed is
 * marked sent anyway, since sending it again would not change the answer.
 */
@Slf4j
public final class ObstacleDataSender
{
	/** Where crossings are sent: the collector, a Cloudflare Worker run by the plugin's author. */
	public static final String URL = "https://golems-dont-die-telemetry.varzeki.workers.dev/v1/crossings";

	/** Crossings in one post; the time between posts while there is a backlog, and after. */
	static final int BATCH = 200;
	static final long CATCH_UP_MILLIS = 60 * 1000;
	static final long INTERVAL_MILLIS = 15 * 60 * 1000;

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;
	private final String pluginVersion;

	/** When the next post may be made, and whether one is still under way. Read on two threads. */
	private volatile long nextDue;
	private volatile boolean sending;

	public ObstacleDataSender(OkHttpClient http, Gson gson, String pluginVersion)
	{
		this.http = http;
		this.gson = gson;
		this.pluginVersion = pluginVersion;
	}

	/**
	 * Sends a batch of what the file has not sent, if sharing is on and it is time.
	 *
	 * @param shared whether the player shares obstacle data
	 * @param now    send whatever the time: the plugin is stopping
	 * @param after  run once the post is answered or given up on, or straight away if none is made;
	 *               may be null
	 */
	public void send(ObstacleDataFile file, boolean shared, boolean now, Runnable after)
	{
		Runnable done = after == null ? () -> { } : after;
		// The player's permission, before anything else.
		if (!shared)
		{
			done.run();
			return;
		}
		long time = System.currentTimeMillis();
		List<ObstacleDataFile.Unsent> batch = URL.isEmpty() || sending || !now && time < nextDue
			? Collections.emptyList() : file.unsent(BATCH);
		if (batch.isEmpty())
		{
			done.run();
			return;
		}
		// A full batch means more is waiting: the next goes soon, until it has all gone.
		nextDue = time + (batch.size() >= BATCH ? CATCH_UP_MILLIS : INTERVAL_MILLIS);
		sending = true;
		try
		{
			Request request = new Request.Builder()
				.url(URL)
				.post(RequestBody.create(JSON, gson.toJson(body(batch, pluginVersion))))
				.build();
			http.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					log.debug("Could not send obstacle data; it goes with the next", e);
					finish(file, batch, false, done);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					int code = response.code();
					response.close();
					if (code >= 400)
					{
						log.debug("Obstacle data not taken ({})", code);
					}
					finish(file, batch, code != 429 && code < 500, done);
				}
			});
		}
		catch (RuntimeException e)
		{
			log.debug("Could not send obstacle data; it goes with the next", e);
			finish(file, batch, false, done);
		}
	}

	/** Ends a post: what the server took (or will never take) is marked sent, and the next may be made. */
	private void finish(ObstacleDataFile file, List<ObstacleDataFile.Unsent> batch, boolean taken, Runnable done)
	{
		if (taken)
		{
			file.markSent(batch);
		}
		sending = false;
		done.run();
	}

	/**
	 * What is posted: the file's schema and the plugin's version, then each crossing as its kind (P
	 * or G), its line, and how many new sightings it carries.
	 */
	static Map<String, Object> body(List<ObstacleDataFile.Unsent> batch, String pluginVersion)
	{
		List<Map<String, Object>> records = new ArrayList<>(batch.size());
		for (ObstacleDataFile.Unsent u : batch)
		{
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("kind", u.kind);
			record.put("row", u.row);
			record.put("count", u.count);
			records.add(record);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("schema", ObstacleDataFile.SCHEMA);
		body.put("plugin", pluginVersion);
		body.put("records", records);
		return body;
	}
}
