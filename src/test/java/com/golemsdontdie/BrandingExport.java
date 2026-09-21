package com.golemsdontdie;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import javax.inject.Inject;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports golems as the game draws them, for the README banners: one at the helm of its raft, and
 * one doing the Party emote under its party lights.
 *
 * <p>Built in the running client exactly as the plugin builds them — the raft as RaftFactory does,
 * the golem as GolemModelFactory does, posed by the helm animation — and written out lit: every
 * face's three vertex colours after the game's own lighting, which is what Creator's Kit sends to
 * Blender. Rendering the cache's raw models offline could only guess at the lighting and could not
 * pose the golem at all.
 *
 * <p>Runs once the client reaches the login screen, writing a file per pose frame to
 * {@code .runelite/golem-exports/}. Dev client only: this lives in the test source set.
 *
 * <p>Which models and animations the Party emote uses were read from the cache with SpotRecon: the
 * emote is sequence 10031, and it shows spot animation 2365, model 47477 playing sequence 10038 and
 * lit 50 ambient and 50 contrast above the usual for a spot animation.
 */
@PluginDescriptor(name = "Branding Export (dev)", description = "Exports golem models for branding", enabledByDefault = true)
public class BrandingExport extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(BrandingExport.class);

	/** Height of the deck, which the golem stands on. Golem.DECK_HEIGHT. */
	private static final int DECK_HEIGHT = 30;

	/** Pose frames to export, as a share of the animation. */
	private static final int FRAMES = 6;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Override
	protected void startUp()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
			{
				return false;
			}
			try
			{
				export();
				exportParty();
				exportStanding();
			}
			catch (Exception e)
			{
				log.warn("Raft export failed", e);
			}
			return true;
		});
	}

	private void export() throws IOException
	{
		Model raft = raft();
		Model golem = golem();
		if (raft == null || golem == null)
		{
			log.warn("Raft export: models not available (raft {}, golem {})", raft != null, golem != null);
			return;
		}

		Animation helm = client.loadAnimation(GolemContent.ANIM_GOLEM_HELM);
		AnimationController controller = new AnimationController(client, helm);
		int frames = helm == null ? 1 : Math.max(1, helm.getDuration());

		File dir = new File(RuneLite.RUNELITE_DIR, "golem-exports");
		dir.mkdirs();
		for (int i = 0; i < FRAMES; i++)
		{
			int frame = frames * i / FRAMES;
			controller.setFrame(frame);
			Model posed = helm == null ? golem : controller.animate(golem);
			File file = new File(dir, "crewed-raft-frame" + frame + ".json");
			try (PrintWriter out = new PrintWriter(file))
			{
				out.print("{\"parts\":[");
				write(out, raft, 0, 0, 0);
				out.print(",");
				// At the helm: the stern tile, stood on the deck.
				write(out, posed, 0, -DECK_HEIGHT, GolemContent.RAFT_HELM_OFFSET);
				out.print("]}");
			}
			log.info("Raft export wrote {}", file);
		}
	}

	/** The Party emote, with its lights. */
	private static final int ANIM_EMOTE_PARTY = 10031;
	private static final int PARTY_LIGHTS_MODEL = 47477;
	private static final int ANIM_PARTY_LIGHTS = 10038;

	/** Client cycles per frame of each, from the cache: the lights run slower than the dance. */
	private static final int EMOTE_CYCLES_PER_FRAME = 5;
	private static final int LIGHTS_CYCLES_PER_FRAME = 7;

	private void exportParty() throws IOException
	{
		Model golem = golem();
		ModelData lightsData = client.loadModelData(PARTY_LIGHTS_MODEL);
		Animation dance = client.loadAnimation(ANIM_EMOTE_PARTY);
		Animation flashing = client.loadAnimation(ANIM_PARTY_LIGHTS);
		if (golem == null || lightsData == null || dance == null || flashing == null)
		{
			log.warn("Party export: not available (golem {}, lights {}, dance {}, flashing {})",
				golem != null, lightsData != null, dance != null, flashing != null);
			return;
		}
		// Spot animation lighting, as Creator's Kit lights them, plus the definition's own boost.
		Model lights = lightsData.cloneVertices().cloneColors().light(64 + 50, 850 + 50, -50, -50, 75);

		AnimationController danceController = new AnimationController(client, dance);
		AnimationController lightsController = new AnimationController(client, flashing);
		File dir = new File(RuneLite.RUNELITE_DIR, "golem-exports");
		dir.mkdirs();
		int frames = dance.getNumFrames();
		for (int frame = 0; frame < frames; frame += 3)
		{
			danceController.setFrame(frame);
			int cycle = frame * EMOTE_CYCLES_PER_FRAME;
			lightsController.setFrame((cycle / LIGHTS_CYCLES_PER_FRAME) % flashing.getNumFrames());
			File file = new File(dir, "party-frame" + frame + ".json");
			try (PrintWriter out = new PrintWriter(file))
			{
				out.print("{\"parts\":[");
				write(out, danceController.animate(golem), 0, 0, 0);
				out.print(",");
				write(out, lightsController.animate(lights), 0, 0, 0);
				out.print("]}");
			}
		}
		log.info("Party export wrote {} frames", (frames + 2) / 3);
	}

	/** The golem standing idle, for the Plugin Hub icon. */
	private void exportStanding() throws IOException
	{
		Model golem = golem();
		Animation idle = client.loadAnimation(GolemContent.GOLEM_IDLE_ANIMATION);
		if (golem == null)
		{
			return;
		}
		File dir = new File(RuneLite.RUNELITE_DIR, "golem-exports");
		dir.mkdirs();
		try (PrintWriter out = new PrintWriter(new File(dir, "golem-rest.json")))
		{
			out.print("{\"parts\":[");
			write(out, golem, 0, 0, 0);
			out.print("]}");
		}
		if (idle != null)
		{
			AnimationController controller = new AnimationController(client, idle);
			try (PrintWriter out = new PrintWriter(new File(dir, "golem-idle.json")))
			{
				out.print("{\"parts\":[");
				write(out, controller.animate(golem), 0, 0, 0);
				out.print("]}");
			}
		}
		log.info("Standing export written");
	}

	private Model raft()
	{
		ModelData hull = client.loadModelData(GolemContent.RAFT_HULL_MODEL);
		ModelData mast = client.loadModelData(GolemContent.RAFT_SAIL_MODEL);
		ModelData cloth = client.loadModelData(GolemContent.RAFT_SAIL_CLOTH_MODEL);
		ModelData helm = client.loadModelData(GolemContent.RAFT_HELM_MODEL);
		if (hull == null || mast == null || cloth == null || helm == null)
		{
			return null;
		}
		hull = hull.cloneVertices().cloneColors();
		for (int i = 0; i < GolemContent.RAFT_HULL_RECOLOUR_FROM.length; i++)
		{
			hull.recolor(GolemContent.RAFT_HULL_RECOLOUR_FROM[i], GolemContent.RAFT_HULL_RECOLOUR_TO[i]);
		}
		helm = helm.cloneVertices().cloneColors();
		for (int i = 0; i < GolemContent.RAFT_HELM_RECOLOUR_FROM.length; i++)
		{
			helm.recolor(GolemContent.RAFT_HELM_RECOLOUR_FROM[i], GolemContent.RAFT_HELM_RECOLOUR_TO[i]);
		}
		helm = helm.translate(0, 0, GolemContent.RAFT_HELM_OFFSET);
		ModelData merged = client.mergeModels(hull, mast.cloneVertices(), cloth.cloneVertices(), helm);
		// RaftFactory's lighting.
		return merged.light(64, 850, -30, -50, -30);
	}

	private Model golem()
	{
		NPCComposition npc = client.getNpcDefinition(GolemContent.GOLEM_NPC_ID);
		if (npc == null || npc.getModels() == null)
		{
			return null;
		}
		int[] ids = npc.getModels();
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		ModelData merged = client.mergeModels(parts, parts.length);
		merged.cloneColors().cloneVertices();
		short[] from = npc.getColorToReplace();
		short[] to = npc.getColorToReplaceWith();
		for (int i = 0; from != null && to != null && i < Math.min(from.length, to.length); i++)
		{
			merged.recolor(from[i], to[i]);
		}
		if (npc.getWidthScale() != 128 || npc.getHeightScale() != 128)
		{
			merged.scale(npc.getWidthScale(), npc.getHeightScale(), npc.getWidthScale());
		}
		// GolemModelFactory's lighting.
		return merged.light(64 + GolemContent.GOLEM_AMBIENT, 850 + GolemContent.GOLEM_CONTRAST, -30, -50, -30);
	}

	/** One model as {vertices, faces: [a, b, c], colours: [c1, c2, c3], alpha, priority}, moved into place. */
	private static void write(PrintWriter out, Model model, int dx, int dy, int dz)
	{
		float[] x = model.getVerticesX();
		float[] y = model.getVerticesY();
		float[] z = model.getVerticesZ();
		out.print("{\"vertices\":[");
		for (int v = 0; v < model.getVerticesCount(); v++)
		{
			out.print((v == 0 ? "" : ",") + "[" + (x[v] + dx) + "," + (y[v] + dy) + "," + (z[v] + dz) + "]");
		}
		out.print("],\"faces\":[");
		int[] a = model.getFaceIndices1();
		int[] b = model.getFaceIndices2();
		int[] c = model.getFaceIndices3();
		int[] c1 = model.getFaceColors1();
		int[] c2 = model.getFaceColors2();
		int[] c3 = model.getFaceColors3();
		byte[] alpha = model.getFaceTransparencies();
		byte[] priority = model.getFaceRenderPriorities();
		for (int f = 0; f < model.getFaceCount(); f++)
		{
			out.print((f == 0 ? "" : ",") + "[" + a[f] + "," + b[f] + "," + c[f] + ","
				+ c1[f] + "," + c2[f] + "," + c3[f] + ","
				+ (alpha == null ? 0 : alpha[f] & 0xff) + ","
				+ (priority == null ? 0 : priority[f]) + "]");
		}
		out.print("]}");
	}
}
