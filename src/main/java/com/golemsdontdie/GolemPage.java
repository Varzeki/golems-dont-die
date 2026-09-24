package com.golemsdontdie;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

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

	private final JFrame frame = new JFrame("Golem Info");
	private final JLabel title = new JLabel();
	private final JLabel place = new JLabel();
	private final JPanel traits = new JPanel();

	/** Where the golem has been lately, one line each, newest first. */
	private final JPanel journal = new JPanel();
	private final JButton find = new JButton("Find");

	/** The scrolling part, so a page opened on another golem starts at the top of it. */
	private final JScrollPane scroll;

	/** The journal's own scrolling part, and the tabs the two of them sit in. */
	private final JScrollPane travels;
	private final javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();

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

		traits.setLayout(new BoxLayout(traits, BoxLayout.Y_AXIS));
		traits.setBackground(ColorScheme.DARK_GRAY_COLOR);
		traits.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));

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

		scroll = new JScrollPane(pinned);
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
		travels = new JScrollPane(journalPinned);
		travels.setBorder(BorderFactory.createEmptyBorder());
		travels.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		travels.getVerticalScrollBar().setUnitIncrement(16);
		travels.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

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
		close.addActionListener(e -> frame.setVisible(false));

		JPanel buttons = new JPanel(new BorderLayout(6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
		buttons.add(find, BorderLayout.CENTER);
		buttons.add(close, BorderLayout.EAST);

		JPanel body = new JPanel(new BorderLayout());
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.add(heading, BorderLayout.NORTH);
		body.add(tabs, BorderLayout.CENTER);
		body.add(buttons, BorderLayout.SOUTH);

		// The client's own chrome, the way the client asks for it: undecorated, and the root pane
		// told to draw a frame. Asking the look and feel first whether it supports decorations
		// said no and left the page in the desktop's chrome.
		try
		{
			frame.setUndecorated(true);
			frame.getRootPane().setWindowDecorationStyle(javax.swing.JRootPane.FRAME);
		}
		catch (RuntimeException e)
		{
			// A look and feel that will not draw a frame leaves us with the desktop's, which is
			// not what we wanted but is a window a player can still move and close.
			frame.dispose();
			frame.setUndecorated(false);
		}
		java.awt.image.BufferedImage icon = net.runelite.client.util.ImageUtil.loadImageResource(
			GolemPage.class, "/golem-icon.png");
		if (icon != null)
		{
			frame.setIconImage(icon);
		}

		// Over the client rather than behind it: a page is opened to be read beside the game.
		frame.setAlwaysOnTop(true);
		frame.setContentPane(body);
		frame.setMinimumSize(new Dimension(320, 260));
		frame.setSize(new Dimension(380, 420));
		// Closing the window keeps the golem: a page is a thing a player glances at and dismisses.
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				frame.setVisible(false);
			}
		});
	}

	/** Opens the page on a golem, or brings it forward if it is already on that one. */
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
		picture.setIcon(null);
		listRecord(golem);
		listTraits(golem);
		listTravels(golem);
		// Every page opens on the golem itself; the journal is there for whoever wants it.
		tabs.setSelectedIndex(0);
		// From the top: the page a player left scrolled halfway is not where the next one starts.
		SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));

		if (!frame.isVisible())
		{
			Window near = beside == null ? null : SwingUtilities.getWindowAncestor(beside);
			frame.setLocationRelativeTo(near);
		}
		frame.setVisible(true);
		frame.toFront();
	}

	/** Whether the page is open, so the plugin only works out a whereabouts line when it is. */
	boolean isOpen()
	{
		return frame.isVisible();
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
	void showPicture(Golem of, java.awt.image.BufferedImage drawn)
	{
		if (of == showing && drawn != null)
		{
			picture.setIcon(new javax.swing.ImageIcon(drawn));
		}
	}

	/** The line under the name: where the golem is, or that it is gone. */
	void showPlace(String where, boolean living)
	{
		place.setText(where == null || where.isEmpty() ? " " : where);
		find.setEnabled(living);
	}

	/** Puts the page away and lets the window go, when the plugin stops. */
	void close()
	{
		showing = null;
		frame.setVisible(false);
		frame.dispose();
	}

	/** What the golem has done, in the plainest words the numbers allow. */
	private void listRecord(Golem golem)
	{
		record.removeAll();
		GolemHistory history = golem.getHistory();

		if (history.getFirstSeen() > 0)
		{
			line(record, "Alive since " + DAY.format(new java.util.Date(history.getFirstSeen())));
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
		record.revalidate();
		record.repaint();
	}

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

	private static final java.text.NumberFormat NUMBERS = java.text.NumberFormat.getIntegerInstance();

	private static final java.text.SimpleDateFormat DAY = new java.text.SimpleDateFormat("d MMM yyyy");

	/**
	 * Where the golem has been lately: the last fifteen regions it arrived in, newest first, each
	 * with how it got there.
	 *
	 * <p>The regions are named here rather than as they happen — the same fifteen names serve
	 * every golem that has been to the same places, and a golem that is never looked at should
	 * cost nothing but the fifteen numbers.
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
			journal.add(row);
		}
		journal.revalidate();
		journal.repaint();
	}

	private void listTraits(Golem golem)
	{
		traits.removeAll();
		JLabel heading = new JLabel("Traits");
		heading.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		heading.setForeground(Color.WHITE);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		traits.add(heading);

		for (GolemTrait trait : GolemTrait.list(golem.getTraits()))
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
			line.setLineWrap(true);
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
			traits.add(one);
		}
		traits.revalidate();
		traits.repaint();
	}

}
