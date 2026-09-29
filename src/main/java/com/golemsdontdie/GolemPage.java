package com.golemsdontdie;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.text.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

/**
 * One golem's own page: what it is called, where it is, and what it is like.
 *
 * <p>A window rather than another sidebar page. A player reading about one golem wants the list
 * still beside it, and a trait and its line do not fit in 225 pixels.
 *
 * <p>Swing thread throughout, and the golem it holds is only ever read: its name and traits, and a
 * whereabouts line the plugin works out on the client thread and hands over. One window, reused —
 * opening a second golem's page replaces what is in it, rather than filling the desktop with
 * windows a player then has to close.
 */
class GolemPage
{
	private static final Font TITLE = FontManager.getRunescapeBoldFont().deriveFont(20f);
	private static final Font BODY = FontManager.getRunescapeFont().deriveFont(16f);

	/**
	 * The page's window, made the first time it is opened. A dialog owned by the client's own window,
	 * so it stays above the client without being put above everything else on the screen, and goes
	 * where the client goes.
	 */
	private JDialog frame;

	/** What the window shows, built once. */
	private final JPanel body = new JPanel(new BorderLayout());
	private final JLabel title = new JLabel();
	private final JLabel place = new JLabel();
	private final JPanel traits = traitList();

	/** Where the golem has been lately, one line each, newest first. */
	private final JPanel journal = new JPanel();
	private final JButton find = new JButton("Find");

	/** The golem the plugin is pointing at, if any: its page's button stops rather than starts. */
	private Golem finding;

	/** The scrolling part, so a page opened on another golem starts at the top of it. */
	private final JScrollPane scroll;

	/** The journal's own scrolling part, and the tabs the two of them sit in. */
	private final JScrollPane travels;
	private final JTabbedPane tabs = new JTabbedPane();

	/** The golem's own picture, in its frame. Empty until the client thread has drawn one. */
	private final JLabel picture = new JLabel();

	/** What the golem has done, filled in beside the picture. */
	private final JPanel record = new JPanel();

	/** Points the arrow at the golem, on the client thread. */
	private final Consumer<Golem> onFind;

	/** What to call a golem nobody has named; see GolemNames. */
	private final GolemNames names;

	/** Names the regions the journal is a list of. */
	private final PlaceNames places;

	/**
	 * The golem the page is showing, or null when it has never been opened. Set on the Swing
	 * thread and read on the client thread, which works out where the golem is.
	 */
	private volatile Golem showing;

	GolemPage(Consumer<Golem> onFind, GolemNames names, PlaceNames places)
	{
		this.onFind = onFind;
		this.names = names;
		this.places = places;

		title.setFont(TITLE);
		title.setForeground(Color.WHITE);
		place.setFont(BODY);
		place.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		// The picture in a frame, as a picture should be: a dark mount and a line around it.
		picture.setPreferredSize(new Dimension(GolemPortrait.WIDTH, GolemPortrait.HEIGHT));
		picture.setHorizontalAlignment(JLabel.CENTER);
		picture.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		picture.setOpaque(true);
		JPanel framed = new JPanel(new BorderLayout());
		framed.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		framed.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 3, 3, 3)));
		framed.add(picture, BorderLayout.CENTER);
		JPanel mount = new JPanel(new BorderLayout());
		mount.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mount.add(framed, BorderLayout.NORTH);

		record.setLayout(new BoxLayout(record, BoxLayout.Y_AXIS));
		record.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel words = new JPanel();
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		words.setBackground(ColorScheme.DARK_GRAY_COLOR);
		words.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		place.setAlignmentX(Component.LEFT_ALIGNMENT);
		record.setAlignmentX(Component.LEFT_ALIGNMENT);
		words.add(title);
		words.add(Box.createVerticalStrut(4));
		words.add(place);
		words.add(Box.createVerticalStrut(10));
		words.add(record);

		JPanel heading = new JPanel(new BorderLayout());
		heading.setBackground(ColorScheme.DARK_GRAY_COLOR);
		heading.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		heading.add(mount, BorderLayout.WEST);
		heading.add(words, BorderLayout.CENTER);

		// The traits are pinned to the top of a panel of their own. In the scroll pane directly,
		// the list is given the whole height of the window and shares the slack out between the
		// traits, which put half a window between two of them.
		JPanel pinned = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		pinned.setBackground(ColorScheme.DARK_GRAY_COLOR);
		pinned.add(traits, BorderLayout.NORTH);

		// Both tabs as tall as five traits, whatever is in them: the window is the same height for
		// every golem, where it had grown to the longest journal it had been shown and stayed there.
		int tabHeight = fullTraitsHeight();
		scroll = new FixedHeight(pinned, tabHeight);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		// Never sideways: the lines wrap to the window, so there is nothing off to the right.
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

		journal.setLayout(new BoxLayout(journal, BoxLayout.Y_AXIS));
		journal.setBackground(ColorScheme.DARK_GRAY_COLOR);
		journal.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
		JPanel journalPinned = new JPanel(new BorderLayout());
		journalPinned.setBackground(ColorScheme.DARK_GRAY_COLOR);
		journalPinned.add(journal, BorderLayout.NORTH);
		travels = new FixedHeight(journalPinned, tabHeight);
		travels.setBorder(BorderFactory.createEmptyBorder());
		travels.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		travels.getVerticalScrollBar().setUnitIncrement(16);
		travels.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

		// One row of tabs whatever the width: wrapping, the tabs asked for a second row when measured
		// before the window had a width, and the first page opened came out a row taller.
		tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		tabs.setFont(BODY);
		tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabs.setForeground(Color.WHITE);
		tabs.addTab("Traits", scroll);
		tabs.addTab("Journal", travels);

		find.setFont(BODY);
		find.setFocusPainted(false);
		find.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		find.setForeground(Color.WHITE);
		find.setToolTipText("Point at this golem until you reach it");
		find.addActionListener(e ->
		{
			if (showing != null)
			{
				onFind.accept(showing);
			}
		});
		JButton close = new JButton("Close");
		close.setFont(BODY);
		close.setFocusPainted(false);
		close.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		close.setForeground(Color.WHITE);
		close.addActionListener(e -> hide());

		JPanel buttons = new JPanel(new BorderLayout(6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
		buttons.add(find, BorderLayout.CENTER);
		buttons.add(close, BorderLayout.EAST);

		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.add(heading, BorderLayout.NORTH);
		body.add(tabs, BorderLayout.CENTER);
		body.add(buttons, BorderLayout.SOUTH);

	}

	/**
	 * Makes the page's window, owned by the client's: the window the sidebar is in, or failing that
	 * the client's own frame.
	 */
	private JDialog window(Component beside)
	{
		Window owner = beside == null ? null : SwingUtilities.getWindowAncestor(beside);
		if (owner == null)
		{
			for (Frame open : Frame.getFrames())
			{
				if (open.isVisible())
				{
					owner = open;
					break;
				}
			}
		}
		JDialog window = new JDialog(owner, "Golem Info");

		// The client's own chrome, the way the client asks for it: undecorated, and the root pane
		// told to draw a frame. Asking the look and feel first whether it supports decorations
		// said no and left the page in the desktop's chrome.
		try
		{
			window.setUndecorated(true);
			window.getRootPane().setWindowDecorationStyle(JRootPane.FRAME);
		}
		catch (RuntimeException e)
		{
			// A look and feel that will not draw a frame leaves us with the desktop's, which is
			// not what we wanted but is a window a player can still move and close.
			window.dispose();
			window.setUndecorated(false);
		}
		BufferedImage icon = ImageUtil.loadImageResource(
			GolemPage.class, "/golem-icon.png");
		if (icon != null)
		{
			window.setIconImage(icon);
		}

		window.setContentPane(body);
		window.setMinimumSize(new Dimension(320, 260));
		window.setSize(new Dimension(380, 420));
		// Closing the window keeps the golem: a page is a thing a player glances at and dismisses.
		window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		window.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				hide();
			}
		});
		return window;
	}

	/** Puts the page away, keeping it for next time. */
	private void hide()
	{
		if (frame != null)
		{
			frame.setVisible(false);
		}
	}

	/** Opens the page on a golem, or turns it to that golem if it is already open. */
	void show(Golem golem, Component beside)
	{
		showing = golem;
		// Named once the client thread has had a look, a frame from now; until then, nothing, and
		// certainly not the last golem's.
		furthest = null;
		String suggested = names == null ? null : names.suggested(golem);
		title.setText(golem.getNickname() != null ? golem.getNickname()
			: suggested != null ? suggested : "Unnamed golem");
		place.setText(" ");
		find.setEnabled(true);
		labelFind();
		picture.setIcon(null);
		listRecord(golem);
		listTraits(golem);
		listTravels(golem);
		// Every page opens on the golem itself; the journal is there for whoever wants it.
		tabs.setSelectedIndex(0);
		// From the top: the page a player left scrolled halfway is not where the next one starts.
		SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));

		if (frame == null)
		{
			frame = window(beside);
		}
		fitHeight();
		if (!frame.isVisible())
		{
			frame.setLocationRelativeTo(frame.getOwner());
		}
		frame.setVisible(true);
	}

	/**
	 * Makes the window tall enough for a golem with every trait it could have, as far as the screen
	 * allows, and the same height for every golem. The tabs ask for that height whatever is in them,
	 * and the record for its longest, so this does not change from one golem to the next.
	 */
	private void fitHeight()
	{
		body.revalidate();
		java.awt.Insets edges = frame.getInsets();
		int wanted = Math.min(body.getPreferredSize().height + edges.top + edges.bottom, usableHeight());
		if (wanted != frame.getHeight())
		{
			frame.setSize(frame.getWidth(), wanted);
		}
	}

	/** A scroll pane that asks for the same height whatever it holds. */
	private static final class FixedHeight extends JScrollPane
	{
		private final int height;

		FixedHeight(Component view, int height)
		{
			super(view);
			this.height = height;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(super.getPreferredSize().width, height);
		}
	}

	/** The panel the traits are listed in, the page's own or one only measured. */
	private static JPanel traitList()
	{
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
		return list;
	}

	/**
	 * How tall the traits tab is with as many traits as a golem can have, a line apiece: laid out
	 * and measured, so it is the page's own fonts and spacing that decide it.
	 */
	private static int fullTraitsHeight()
	{
		JPanel sample = traitList();
		GolemTrait[] all = GolemTrait.values();
		fillTraits(sample, java.util.Arrays.asList(all).subList(0, Math.min(GolemTrait.MOST_TRAITS, all.length)), false);
		return sample.getPreferredSize().height;
	}

	private int usableHeight()
	{
		java.awt.GraphicsConfiguration screen = frame.getGraphicsConfiguration();
		if (screen == null)
		{
			return 700;
		}
		java.awt.Insets taskbar = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(screen);
		return screen.getBounds().height - taskbar.top - taskbar.bottom - 40;
	}

	/** Tells the page which golem is being found, so its button reads Find or Stop finding. */
	void setFinding(Golem target)
	{
		finding = target;
		labelFind();
	}

	private void labelFind()
	{
		boolean stopping = finding != null && finding == showing;
		find.setText(stopping ? "Stop finding" : "Find");
		find.setToolTipText(stopping ? "Stop pointing at this golem" : "Point at this golem until you reach it");
	}

	/** Whether the page is open, so the plugin only works out a whereabouts line when it is. */
	boolean isOpen()
	{
		return frame != null && frame.isVisible();
	}

	Golem getShowing()
	{
		return showing;
	}

	/**
	 * Hangs the golem's picture, drawn on the client thread after the page was opened.
	 *
	 * @param of the golem it is of, ignored if the page has moved on to another
	 */
	void showPicture(Golem of, BufferedImage drawn)
	{
		if (of == showing && drawn != null)
		{
			picture.setIcon(new ImageIcon(drawn));
		}
	}

	/** The line under the name: where the golem is, or that it is gone. */
	/**
	 * @param of the golem the place was worked out for: ignored if the page has moved on to another,
	 *           as a late picture is
	 */
	void showPlace(Golem of, String where, boolean living)
	{
		if (of != showing)
		{
			return;
		}
		place.setText(where == null || where.isEmpty() ? " " : where);
		find.setEnabled(living);
	}

	/** Puts the page away and lets the window go, when the plugin stops. */
	void close()
	{
		showing = null;
		if (frame != null)
		{
			frame.setVisible(false);
			frame.dispose();
			frame = null;
		}
	}

	/** What the golem has done, in the plainest words the numbers allow. */
	private void listRecord(Golem golem)
	{
		record.removeAll();
		GolemHistory history = golem.getHistory();

		if (history.getFirstSeen() > 0)
		{
			line(record, "Alive since " + DAY.format(new Date(history.getFirstSeen())));
		}
		line(record, "Walked about " + NUMBERS.format(history.getWalked()) + " tiles");
		line(record, history.getTransports() == 1 ? "Used one shortcut"
			: "Used " + NUMBERS.format(history.getTransports()) + " shortcuts");
		if (history.getVoyages() > 0)
		{
			line(record, history.getVoyages() == 1 ? "Sailed once"
				: "Sailed " + NUMBERS.format(history.getVoyages()) + " times");
		}
		if (history.getFurthest() > 0)
		{
			String where = furthest == null ? "" : " — " + furthest;
			line(record, "Been " + NUMBERS.format(history.getFurthest()) + " tiles from home" + where);
		}
		// Blank lines to the most the record can have, so every golem's heading is the same height.
		while (record.getComponentCount() < RECORD_LINES)
		{
			line(record, " ");
		}
		record.revalidate();
		record.repaint();
	}

	/** The most lines the record can have: alive since, walked, shortcuts, sailed, furthest. */
	private static final int RECORD_LINES = 5;

	/** One line of the record. */
	private static void line(JPanel into, String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		into.add(label);
	}

	/** What to call the furthest place the golem has been, or null if nothing knows. */
	private String furthest;

	/** Told after the page is up, because naming a place is the client thread's business. */
	/**
	 * @param of the golem the place was worked out for: ignored if the page has moved on to another,
	 *           the way {@link #showPicture} ignores a late picture
	 */
	void setFurthest(Golem of, String place)
	{
		if (of == null || of != showing)
		{
			return;
		}
		furthest = place;
		listRecord(showing);
	}

	private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance();

	private static final SimpleDateFormat DAY = new SimpleDateFormat("d MMM yyyy");

	/**
	 * Where the golem has been lately: the last twenty places it arrived in, newest first, each
	 * with how it got there.
	 *
	 * <p>The regions are named here rather than as they happen — the same names serve
	 * every golem that has been to the same places, and a golem that is never looked at should
	 * cost nothing but twenty numbers.
	 */
	private void listTravels(Golem golem)
	{
		journal.removeAll();
		int[][] entries = golem.getHistory().travels();
		int named = 0;
		for (int[] entry : entries)
		{
			int region = entry[0];
			named += places != null
				&& places.nameFor((region >> 8) * 64 + 32, (region & 0xff) * 64 + 32, entry[1]) != null ? 1 : 0;
		}
		if (named == 0)
		{
			JLabel nothing = new JLabel("Nowhere yet.");
			nothing.setFont(BODY);
			nothing.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			nothing.setAlignmentX(Component.LEFT_ALIGNMENT);
			journal.add(nothing);
			journal.revalidate();
			journal.repaint();
			return;
		}

		JLabel order = new JLabel("Newest first");
		order.setFont(FontManager.getRunescapeSmallFont());
		order.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		order.setAlignmentX(Component.LEFT_ALIGNMENT);
		order.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		journal.add(order);

		int now = (int) (System.currentTimeMillis() / 60_000L);
		for (int[] entry : entries)
		{
			int region = entry[0];
			int plane = entry[1];
			// The middle of the region, which is what names it.
			int x = (region >> 8) * 64 + 32;
			int y = (region & 0xff) * 64 + 32;
			String place = places == null ? null : places.nameFor(x, y, plane);
			if (place == null)
			{
				// Country with no name on it. A journal of "somewhere unmapped" says nothing
				// about where a golem has been, so the entry is passed over rather than written.
				continue;
			}

			JTextArea line = new JTextArea(GolemTravel.byOrdinal(entry[2]).line(place));
			line.setFont(BODY);
			line.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			line.setBackground(ColorScheme.DARK_GRAY_COLOR);
			line.setLineWrap(true);
			line.setWrapStyleWord(true);
			line.setEditable(false);
			line.setFocusable(false);
			line.setAlignmentX(Component.LEFT_ALIGNMENT);
			line.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

			// A panel apiece, as the traits are: a list of text areas given the window's height
			// shares the slack out between them and leaves gaps.
			JPanel row = new JPanel(new BorderLayout())
			{
				@Override
				public Dimension getMaximumSize()
				{
					return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
				}
			};
			row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(line, BorderLayout.CENTER);
			JLabel ago = new JLabel(ago(now - entry[3]));
			ago.setFont(FontManager.getRunescapeSmallFont());
			ago.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			ago.setVerticalAlignment(JLabel.TOP);
			ago.setBorder(BorderFactory.createEmptyBorder(2, 8, 0, 0));
			row.add(ago, BorderLayout.EAST);
			journal.add(row);
		}
		journal.revalidate();
		journal.repaint();
	}

	/** How long ago, for a journal entry, from minutes. */
	private static String ago(int minutes)
	{
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + "m ago";
		}
		if (minutes < 60 * 24)
		{
			return minutes / 60 + "h ago";
		}
		return minutes / (60 * 24) + "d ago";
	}

	private void listTraits(Golem golem)
	{
		fillTraits(traits, GolemTrait.list(golem.getTraits()), true);
	}

	/** Lists traits into a panel: a heading, then each trait's name over its line. */
	private static void fillTraits(JPanel into, List<GolemTrait> list, boolean wrap)
	{
		into.removeAll();
		JLabel heading = new JLabel("Traits");
		heading.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		heading.setForeground(Color.WHITE);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		into.add(heading);

		for (GolemTrait trait : list)
		{
			JLabel label = new JLabel(trait.getLabel());
			label.setFont(BODY);
			label.setForeground(ColorScheme.BRAND_ORANGE);

			// A text area rather than a label, for the wrapping: a trait's line is a sentence, the
			// window is narrow, and it is resizable, so the width to wrap at is not ours to pick.
			JTextArea line = new JTextArea(trait.getDescription());
			line.setFont(FontManager.getRunescapeSmallFont());
			line.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			line.setBackground(ColorScheme.DARK_GRAY_COLOR);
			// Measured unwrapped: a wrapping text area not yet given a width has no height to go by.
			line.setLineWrap(wrap);
			line.setWrapStyleWord(true);
			line.setEditable(false);
			line.setFocusable(false);

			// Each trait in a panel of its own, so the list cannot stretch the text areas to fill
			// the window: a page with two traits on it had half a window between them.
			JPanel one = new JPanel(new BorderLayout());
			one.setBackground(ColorScheme.DARK_GRAY_COLOR);
			one.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
			one.setAlignmentX(Component.LEFT_ALIGNMENT);
			one.add(label, BorderLayout.NORTH);
			one.add(line, BorderLayout.CENTER);
			into.add(one);
		}
		into.revalidate();
		into.repaint();
	}

}
