package com.golemsdontdie;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.Client;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Records how boats really move, so golem rafts can be made to move the same way.
 *
 * <p>Nothing in the cache says how fast a boat turns or how it moves between tiles: the
 * steering tables are empty client-side and the movement is decided by the server. So this
 * watches every boat in view and writes down, on every client frame, where the client draws
 * it ({@code getLocalLocation}, {@code getOrientation}) and where the server told it to be
 * ({@code getTargetLocation}, {@code getTargetOrientation}). A row is written only when one
 * of those changes. Heading clicks and speed changes are logged alongside.
 *
 * <p>Output goes to {@code .runelite/boat-recon-<time>.csv}. Dev client only: this lives in
 * the test source set and is never shipped.
 */
@PluginDescriptor(name = "Boat Motion Recon (dev)", description = "Logs boat movement for golem sailing", enabledByDefault = true)
public class BoatMotionRecon extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(BoatMotionRecon.class);

	@Inject
	private Client client;

	private PrintWriter out;
	private final Map<Integer, String> last = new HashMap<>();

	@Override
	protected void startUp() throws IOException
	{
		File file = new File(RuneLite.RUNELITE_DIR,
			"boat-recon-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv");
		out = new PrintWriter(new FileWriter(file), true);
		out.println("millis,cycle,tick,kind,view,owner,config,x,y,orient,targetX,targetY,targetOrient,note");
		log.info("Boat recon writing to {}", file);
	}

	@Override
	protected void shutDown()
	{
		if (out != null)
		{
			out.close();
			out = null;
		}
		last.clear();
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		WorldView top = client.getTopLevelWorldView();
		if (out == null || top == null)
		{
			return;
		}
		for (WorldEntity boat : top.worldEntities())
		{
			LocalPoint at = boat.getLocalLocation();
			LocalPoint target = boat.getTargetLocation();
			if (at == null || target == null)
			{
				continue;
			}
			int view = boat.getWorldView().getId();
			// World coordinates in 1/128ths of a tile, so rows stay comparable across scene reloads.
			String state = fine(top.getBaseX(), at.getX()) + "," + fine(top.getBaseY(), at.getY()) + ","
				+ boat.getOrientation() + "," + fine(top.getBaseX(), target.getX()) + ","
				+ fine(top.getBaseY(), target.getY()) + "," + boat.getTargetOrientation();
			if (!state.equals(last.put(view, state)))
			{
				row("frame", view, boat.getOwnerType(), boat.getConfig() == null ? -1 : boat.getConfig().getId(), state, "");
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (out != null)
		{
			out.println(System.currentTimeMillis() + "," + client.getGameCycle() + "," + client.getTickCount() + ",tick,,,,,,,,,,"
				+ "move=" + client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE)
				+ " base=" + client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED)
				+ " cap=" + client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_SPEEDCAP)
				+ " accel=" + client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION));
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (out != null && event.getVarbitId() == VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE)
		{
			row("move", -1, -1, -1, ",,,,,", "mode=" + event.getValue());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Only while aboard: the player then lives in the boat's world view, not the top level.
		if (out != null && client.getLocalPlayer() != null
			&& client.getLocalPlayer().getWorldView() != client.getTopLevelWorldView())
		{
			row("click", -1, -1, -1, ",,,,,", (event.getMenuOption() + " p0=" + event.getParam0()
				+ " p1=" + event.getParam1() + " id=" + event.getId() + " action=" + event.getMenuAction()).replace(',', ' '));
		}
	}

	private void row(String kind, int view, int owner, int config, String state, String note)
	{
		out.println(System.currentTimeMillis() + "," + client.getGameCycle() + "," + client.getTickCount() + ","
			+ kind + "," + view + "," + owner + "," + config + "," + state + "," + note);
	}

	private static int fine(int base, int local)
	{
		return base * 128 + local;
	}
}
