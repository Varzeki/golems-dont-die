package com.golemsdontdie;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.text.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import net.runelite.client.ui.*;

/**
 * Side panel listing every golem, with a name field and a remove button each.
 *
 * <p>A panel rather than config items because the list is not a setting: it grows and shrinks
 * as golems are made and retired, and RuneLite's config UI is generated from a fixed interface,
 * so a dynamic list with per-row controls must be built by hand. It replaces a "forget saved
 * golems" tick box that could only delete every golem at once.
 */
class GolemListPanel extends PluginPanel
{
	private static final Font HEADING = FontManager.getRunescapeBoldFont().deriveFont(18f);
	private static final Font ROW = FontManager.getRunescapeFont().deriveFont(16f);
	private static final Font PLACEHOLDER = ROW.deriveFont(Font.ITALIC);

	/** Dim enough to read as absent text rather than as a name someone chose. */
	private static final Color PLACEHOLDER_COLOUR = new Color(120, 120, 120);

	/** Golems listed at once. A player who has crafted for months has thousands. */
	private static final int PER_PAGE = 15;

	/**
	 * The smallest a name or a place may be squeezed to, in pixels.
	 *
	 * <p>Without it the row is as wide as its longest line of text and the buttons at its end are
	 * pushed off the edge, under the scrollbar: a text field and a label both ask for room enough
	 * for all of their text, and a layout takes that as a floor.
	 */
	private static final Dimension SQUEEZED = new Dimension(24, 16);

	/**
	 * The two button icons, painted rather than stored as pixels.
	 *
	 * <p>A fourteen pixel image is fourteen pixels, and on a screen the client is scaling it is a
	 * blurred fourteen pixels. Painting into the graphics it is given puts the lines wherever the
	 * scale asks for them.
	 */
	private static final Icon INFO = new Drawn(Drawn.INFO);
	private static final Icon TARGET = new Drawn(Drawn.TARGET);
	private static final Icon REMOVE = new Drawn(Drawn.REMOVE);

	/**
	 * An (i) in a ring, a ring with a cross through it, or an X: the three buttons of a row.
	 *
	 * <p>Thirteen pixels, not fourteen, so there is a middle pixel to centre on: in fourteen the
	 * middle is a line between two pixels, and a one-pixel stroke down it was pushed to one side.
	 * Every shape here is centred on 6.5, the middle of pixel six, with strokes on pixel centres.
	 * The X was a character of the font, at the font's size and on its baseline, and it is drawn
	 * now like the others.
	 */
	private static final class Drawn implements Icon
	{
		static final int INFO = 0;
		static final int TARGET = 1;
		static final int REMOVE = 2;

		private static final int SIZE = 13;

		private final int kind;

		private Drawn(int kind)
		{
			this.kind = kind;
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}

		@Override
		public void paintIcon(Component on, Graphics g, int x, int y)
		{
			Graphics2D drawing = (Graphics2D) g.create();
			drawing.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			drawing.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			drawing.translate(x, y);
			drawing.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			drawing.setStroke(new BasicStroke(1f));
			switch (kind)
			{
				case TARGET:
					// A ring with a cross through it: what the hint arrow does at the other end.
					drawing.draw(new Ellipse2D.Float(3f, 3f, 7f, 7f));
					drawing.draw(new Line2D.Float(6.5f, 0f, 6.5f, 2f));
					drawing.draw(new Line2D.Float(6.5f, 11f, 6.5f, 13f));
					drawing.draw(new Line2D.Float(0f, 6.5f, 2f, 6.5f));
					drawing.draw(new Line2D.Float(11f, 6.5f, 13f, 6.5f));
					break;
				case REMOVE:
					drawing.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					drawing.draw(new Line2D.Float(3.5f, 3.5f, 9.5f, 9.5f));
					drawing.draw(new Line2D.Float(9.5f, 3.5f, 3.5f, 9.5f));
					break;
				default:
					drawing.draw(new Ellipse2D.Float(0.5f, 0.5f, 12f, 12f));
					drawing.fill(new Rectangle2D.Float(6f, 3f, 1f, 1f));
					drawing.fill(new Rectangle2D.Float(6f, 5f, 1f, 5f));
					break;
			}
			drawing.dispose();
		}
	}

	private final JPanel rows = new JPanel();
	private final JLabel summary = new JLabel();

	/** Narrows the list to golems whose name contains what is typed. */
	private final JTextField search = new PlaceholderField(null, "Search golems");

	private final JButton previous = new JButton("<");
	private final JButton next = new JButton(">");
	private final JLabel pageLabel = new JLabel();
	private final JPanel paging = new JPanel(new BorderLayout());

	/**
	 * Where the rows would be, when a search matches none of them. Not on the pager, which is hidden
	 * with fewer than two pages and so is never there to say it.
	 */
	private final JLabel nothingFound = new JLabel("No golems found");

	/** Says what the order is, under the pager: starred, then nearest, and nothing else said so. */
	private final JLabel order = new JLabel("Starred first, then closest");

	/** The whole roster, as the client thread last gave it. */
	private List<Golem> roster = Collections.emptyList();

	/** The golems matching the search, in the roster's order. */
	private List<Golem> matching = Collections.emptyList();

	/** The golems on the page now, for whoever wants to know what is worth updating. */
	private volatile List<Golem> onScreen = Collections.emptyList();

	private int page;

	/**
	 * Offers to make up the difference when fewer golems are roaming than have been crafted.
	 * Hidden whenever the two agree.
	 */
	private final JButton revive = new JButton();

	private final Consumer<Golem> onRemove;

	/** Asks for a golem to be pointed at, or for the pointing to stop when given null. */
	private final Consumer<Golem> onFind;

	/** Stars a golem, or takes its star away. */
	private final Consumer<Golem> onStar;

	/** Asks for one golem's own page to be opened. */
	private final Consumer<Golem> onOpen;

	/** What to call a golem nobody has named; see GolemNames. */
	private final GolemNames names;

	/** The bar offering to stop pointing, shown only while a golem is being pointed at. */
	private final JButton finding = new JButton();

	/** A golem and its new name, or null for none. Called on the Swing thread. */
	private final BiConsumer<Golem, String> onRename;

	private final Runnable onRevive;

	/**
	 * The row showing each golem, keyed by the golem itself: one restored at login is a new
	 * object, and a row holding the old one would rename a golem no longer in the roster.
	 */
	private final Map<Golem, Row> shown = new IdentityHashMap<>();

	/** One golem's row: what goes in the list, the name field in it, and where the golem is. */
	private static final class Row
	{
		final JPanel component;
		final JTextField name;
		JLabel place;

		/** Set while the name is being put in from the game, so it is not taken for typing. */
		boolean settingName;

		Row(JPanel component, JTextField name)
		{
			this.component = component;
			this.name = name;
		}
	}

	GolemListPanel(GolemNames names, Consumer<Golem> onRemove,
		BiConsumer<Golem, String> onRename, Runnable onRevive,
		Consumer<Golem> onFind, Consumer<Golem> onStar, Consumer<Golem> onOpen)
	{
		super(false);
		this.names = names;
		this.onRemove = onRemove;
		this.onFind = onFind;
		this.onStar = onStar;
		this.onOpen = onOpen;
		this.onRename = onRename;
		this.onRevive = onRevive;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(16, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		summary.setFont(HEADING);
		summary.setForeground(Color.WHITE);
		summary.setHorizontalAlignment(SwingConstants.CENTER);
		summary.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

		revive.setFont(ROW);
		revive.setFocusPainted(false);
		revive.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		revive.setForeground(Color.WHITE);
		revive.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		revive.addActionListener(e -> onRevive.run());
		revive.setVisible(false);

		search.setFont(ROW);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				searched();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				searched();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				searched();
			}

			private void searched()
			{
				page = 0;
				relist();
			}
		});

		for (JButton button : new JButton[]{previous, next})
		{
			button.setFont(ROW);
			button.setFocusPainted(false);
			button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			button.setForeground(Color.WHITE);
			button.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
		}
		previous.addActionListener(e -> turnTo(page - 1));
		next.addActionListener(e -> turnTo(page + 1));
		pageLabel.setFont(ROW);
		pageLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageLabel.setHorizontalAlignment(SwingConstants.CENTER);

		paging.setBackground(ColorScheme.DARK_GRAY_COLOR);
		paging.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		paging.add(previous, BorderLayout.WEST);
		paging.add(pageLabel, BorderLayout.CENTER);
		paging.add(next, BorderLayout.EAST);
		paging.setVisible(false);

		finding.setFont(ROW);
		finding.setFocusPainted(false);
		finding.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		finding.setForeground(Color.WHITE);
		finding.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		finding.addActionListener(e -> onFind.accept(null));
		finding.setVisible(false);

		// A gap under the two buttons, which otherwise sit straight on top of the search field.
		JPanel above = new JPanel(new BorderLayout());
		above.setBackground(ColorScheme.DARK_GRAY_COLOR);
		above.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		above.add(revive, BorderLayout.NORTH);
		above.add(finding, BorderLayout.SOUTH);

		order.setFont(FontManager.getRunescapeSmallFont());
		order.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		order.setHorizontalAlignment(SwingConstants.CENTER);
		order.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JPanel below = new JPanel(new BorderLayout());
		below.setBackground(ColorScheme.DARK_GRAY_COLOR);
		below.add(paging, BorderLayout.NORTH);
		below.add(order, BorderLayout.SOUTH);

		JPanel controls = new JPanel(new BorderLayout());
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.add(above, BorderLayout.NORTH);
		controls.add(search, BorderLayout.CENTER);
		controls.add(below, BorderLayout.SOUTH);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
		header.add(summary, BorderLayout.NORTH);
		header.add(controls, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Rows are pinned to the top of a filler panel so a short list does not stretch
		// down the whole panel.
		JPanel filler = new Tracking();
		filler.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filler.add(rows, BorderLayout.NORTH);
		nothingFound.setFont(ROW);
		nothingFound.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nothingFound.setHorizontalAlignment(SwingConstants.CENTER);
		nothingFound.setVerticalAlignment(SwingConstants.TOP);
		nothingFound.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		nothingFound.setVisible(false);
		filler.add(nothingFound, BorderLayout.CENTER);

		JScrollPane scroll = new JScrollPane(filler,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
	}

	/**
	 * As {@link #refresh(List, int, boolean)}, leaving the names on rows already shown alone.
	 *
	 * @param missing how many fewer golems are roaming than crafted; the revive button shows
	 *                only when this is positive
	 */
	void refresh(List<Golem> golems, int missing)
	{
		refresh(golems, missing, false);
	}

	/**
	 * Builds every row again, for when the name pack changes: the name a row shows for a golem
	 * nobody has named is a prompt set when the row was made.
	 */
	void namesChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			shown.clear();
			rows.removeAll();
			relist();
		});
	}

	/**
	 * Puts the list back in order, unless the player is typing in it or has the mouse over it.
	 *
	 * <p>Reordering moves rows, and a text field taken out of the list and put back loses the keyboard:
	 * a name half typed carried on into the game. So while a name or the search box has the caret the
	 * order waits, and the next reorder after the player stops typing puts it right.
	 */
	void reorder(List<Golem> golems, int missing)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (typing())
			{
				return;
			}
			// Nor under the mouse: a row moved out from under the pointer gives the click meant for it
			// to another golem. Golems still come and go; the order waits for the mouse to leave.
			roster = pointedAt() ? inPlace(golems) : golems;
			updateRevive(missing);
			updateSummary(golems.size());
			relist();
		});
	}

	/** Whether the keyboard is in one of this panel's text fields: a name, or the search. */
	private boolean typing()
	{
		if (search.isFocusOwner())
		{
			return true;
		}
		for (Row row : shown.values())
		{
			if (row.name.isFocusOwner())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the mouse is anywhere over this panel. Asked of where the pointer is on the screen
	 * rather than kept from enter and exit events, which each row, button and field has its own of,
	 * so that moving from one to the next inside the panel is not taken for leaving it.
	 */
	private boolean pointedAt()
	{
		if (!isShowing())
		{
			return false;
		}
		PointerInfo pointer = MouseInfo.getPointerInfo();
		if (pointer == null)
		{
			return false;
		}
		Point at = pointer.getLocation();
		SwingUtilities.convertPointFromScreen(at, this);
		return contains(at);
	}

	/**
	 * Takes the roster as it now stands, in the order the client thread put it: nearest first.
	 *
	 * <p>Only a page of it is ever built. One row is half a dozen Swing components, and a player who
	 * has crafted for months has thousands of golems — sixty thousand components in one scroll pane
	 * is minutes of laying out and megabytes held for a list nobody can read anyway.
	 *
	 * @param missing how many fewer golems are roaming than crafted; the revive button shows
	 *                only when this is positive
	 * @param names   also take names given in game, which leave the roster otherwise unchanged
	 */
	void refresh(List<Golem> golems, int missing, boolean names)
	{
		SwingUtilities.invokeLater(() ->
		{
			// While a name is being typed the order stands, as it does for reorder: a row moved is a
			// field that loses the keyboard. So too while the mouse is over the list. Golems still
			// come and go.
			roster = typing() || pointedAt() ? inPlace(golems) : golems;
			updateRevive(missing);
			updateSummary(golems.size());
			relist();
			if (names)
			{
				for (Map.Entry<Golem, Row> entry : shown.entrySet())
				{
					showName(entry.getValue(), entry.getKey());
				}
			}
		});
	}

	/** The golems given, in the order the list has them now, and any new ones after. */
	private List<Golem> inPlace(List<Golem> golems)
	{
		Set<Golem> fresh = Collections.newSetFromMap(new IdentityHashMap<>());
		fresh.addAll(golems);
		List<Golem> kept = new ArrayList<>(golems.size());
		for (Golem golem : roster)
		{
			if (fresh.remove(golem))
			{
				kept.add(golem);
			}
		}
		for (Golem golem : golems)
		{
			if (fresh.contains(golem))
			{
				kept.add(golem);
			}
		}
		return kept;
	}

	/** Works out who is on the page now, and builds the rows for them. */
	private void relist()
	{
		String wanted = search.getText() == null ? "" : search.getText().trim().toLowerCase();
		List<Golem> found = new ArrayList<>();
		for (Golem golem : roster)
		{
			// Searched by whatever the row says, which for a golem nobody has named is the name
			// the plugin gives it: a player looking for Pebblesworth means the one called that.
			String name = golem.getNickname() == null ? names.suggested(golem) : golem.getNickname();
			if (wanted.isEmpty() || name != null && name.toLowerCase().contains(wanted))
			{
				found.add(golem);
			}
		}
		matching = found;

		int pages = Math.max(1, (matching.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.max(0, Math.min(page, pages - 1));
		int from = page * PER_PAGE;
		int to = Math.min(matching.size(), from + PER_PAGE);
		List<Golem> wantedRows = matching.subList(from, to);
		onScreen = new ArrayList<>(wantedRows);

		// Rows belong to golems, not to places in the list: a row kept is a name still being typed.
		Set<Golem> keep = Collections.newSetFromMap(new IdentityHashMap<>());
		keep.addAll(wantedRows);
		Iterator<Map.Entry<Golem, Row>> it = shown.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Golem, Row> entry = it.next();
			if (!keep.contains(entry.getKey()))
			{
				rows.remove(entry.getValue().component);
				it.remove();
			}
		}

		boolean built = false;
		for (int i = 0; i < wantedRows.size(); i++)
		{
			Golem golem = wantedRows.get(i);
			Row row = shown.get(golem);
			if (row == null)
			{
				row = row(golem);
				shown.put(golem, row);
				built = true;
				rows.add(row.component, Math.min(i, rows.getComponentCount()));
			}
			else if (i >= rows.getComponentCount() || rows.getComponent(i) != row.component)
			{
				rows.remove(row.component);
				rows.add(row.component, Math.min(i, rows.getComponentCount()));
			}
		}

		if (built)
		{
			onPlacesWanted.run();
		}

		paging.setVisible(pages > 1);
		previous.setEnabled(page > 0);
		next.setEnabled(page < pages - 1);
		pageLabel.setText("Page " + (page + 1) + " of " + pages + "  (" + matching.size() + ")");
		// Only for a search: an empty roster is already said, as "No golems yet", above.
		nothingFound.setVisible(matching.isEmpty() && !wanted.isEmpty());
		rows.revalidate();
		rows.repaint();
	}

	private void turnTo(int wanted)
	{
		page = wanted;
		relist();
	}

	/** The golems listed on the page now. Read from the client thread, which updates their places. */
	List<Golem> onScreenGolems()
	{
		return onScreen;
	}

	/**
	 * Says where each golem on the page is and how far off, under its name.
	 *
	 * <p>Given as lists in step with the golems rather than read from them here: they belong to the
	 * client thread, and this is Swing's.
	 *
	 * @param places   where each golem is, in words
	 * @param distance how many tiles away each one is, or -1 where that cannot be said
	 */
	void showPlaces(List<Golem> golems, List<String> places, List<Integer> distance)
	{
		SwingUtilities.invokeLater(() ->
		{
			for (int i = 0; i < golems.size() && i < places.size(); i++)
			{
				Row row = shown.get(golems.get(i));
				if (row == null || row.place == null)
				{
					continue;
				}
				// How far off first, then where: a row is two hundred pixels wide and "Sailing to
				// Port Khazard" fills most of it, so whichever goes second is the one that clips.
				int away = i < distance.size() ? distance.get(i) : -1;
				String said = away < 0 ? places.get(i)
					: TILES.format(away) + " tiles away · " + places.get(i);
				lastSaid.put(golems.get(i), said);
				if (!row.place.getText().equals(said))
				{
					row.place.setText(said);
					// The row is 200 pixels wide and a place can be "Sailing to Port Khazard", so
					// what will not fit is still readable by hovering it.
					row.place.setToolTipText(said);
				}
			}
		});
	}

	private static final NumberFormat TILES = NumberFormat.getIntegerInstance();

	/**
	 * What each golem's place line last said, so a row built again, on turning the page or
	 * searching, says it at once rather than standing blank until the next update.
	 */
	private final Map<Golem, String> lastSaid = new WeakHashMap<>();

	/** Asked for when new rows appear, so their places are worked out on the next tick. */
	@lombok.Setter
	private Runnable onPlacesWanted = () -> { };

	/** Shows or hides the bar offering to stop pointing at a golem. */
	void setFinding(String name)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean show = name != null;
			if (show)
			{
				finding.setText("Stop finding " + name);
			}
			if (finding.isVisible() != show)
			{
				finding.setVisible(show);
				revalidate();
				repaint();
			}
		});
	}

	/** True while the panel is on screen: nothing else is worth updating. */
	boolean isOnScreen()
	{
		return isShowing();
	}

	/** How fast a hovered name too long for its field scrolls, in pixels a second. */
	private static final int MARQUEE_SPEED = 30;

	/** How long it rests at each end, in milliseconds, so the ends can be read. */
	private static final int MARQUEE_REST = 1200;

	/**
	 * Scrolls the hovered name back and forth when it is too long for its field, so the whole of it
	 * can be read without widening the field into the buttons' room. One timer for the panel, and
	 * running only while a name is hovered.
	 */
	private final Timer marquee = new Timer(40, e -> scrollHovered());

	/** The name field under the mouse, and when the mouse came onto it. */
	private PlaceholderField hovered;
	private long hoveredSince;

	private void hover(PlaceholderField field)
	{
		hovered = field;
		hoveredSince = System.currentTimeMillis();
		marquee.start();
	}

	private void leave(PlaceholderField field)
	{
		if (hovered != field)
		{
			return;
		}
		hovered = null;
		marquee.stop();
		// Back to the beginning of the name, unless the caret is in it: then it is the caret's to place.
		if (!field.isFocusOwner() || field.getText().isEmpty())
		{
			field.scrollTo(0);
		}
	}

	private void scrollHovered()
	{
		PlaceholderField field = hovered;
		if (field == null || !field.isShowing())
		{
			// The row went, rebuilt or turned off the page, without the mouse ever leaving it.
			hovered = null;
			marquee.stop();
			return;
		}
		if (field.isFocusOwner())
		{
			// A name being typed stays where the caret put it: text moving under the caret would
			// fight every keypress. Started over from the beginning once the caret has gone.
			hoveredSince = System.currentTimeMillis();
			if (field.getText().isEmpty())
			{
				field.scrollTo(0);
			}
			return;
		}
		field.scrollTo(marqueeOffset(field.overflow(), System.currentTimeMillis() - hoveredSince));
	}

	/**
	 * How far along a name should be shown this long into the hover: resting at the beginning,
	 * scrolled to the end, resting there, scrolled back, and round again.
	 */
	private static int marqueeOffset(int overflow, long elapsed)
	{
		if (overflow <= 0)
		{
			return 0;
		}
		long travel = overflow * 1000L / MARQUEE_SPEED;
		long at = elapsed % (2 * (MARQUEE_REST + travel));
		if (at < MARQUEE_REST)
		{
			return 0;
		}
		at -= MARQUEE_REST;
		if (at < travel)
		{
			return (int) (overflow * at / travel);
		}
		at -= travel;
		if (at < MARQUEE_REST)
		{
			return overflow;
		}
		at -= MARQUEE_REST;
		return (int) (overflow - overflow * at / travel);
	}

	/** Puts a golem's name into its field, if it differs, without that counting as typing. */
	private static void showName(Row row, Golem golem)
	{
		String name = golem.getNickname() == null ? "" : golem.getNickname();
		if (!row.name.getText().trim().equals(name))
		{
			row.settingName = true;
			try
			{
				row.name.setText(name);
				// A field longer than the box scrolls to wherever the caret is, and setText leaves
				// it at the end: a golem called something long showed the last of its name with the
				// first of it off the left edge. Wound back so a name reads from its beginning.
				row.name.setCaretPosition(0);
			}
			finally
			{
				row.settingName = false;
			}
		}
	}

	/**
	 * Shows or hides the revive button: only present when there is something to revive, since a
	 * button that does nothing most of the time is worse than no button.
	 */
	private void updateRevive(int missing)
	{
		boolean show = missing > 0;
		if (show)
		{
			revive.setText("Revive missing golems (" + missing + ")");
		}
		if (revive.isVisible() != show)
		{
			revive.setVisible(show);
			revalidate();
			repaint();
		}
	}

	private void updateSummary(int count)
	{
		summary.setText(count == 0
			? "No golems yet"
			: count + (count == 1 ? " golem" : " golems") + " unleashed");
	}

	private Row row(Golem golem)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 6));
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));

		// Where the golem is, under its name: what it last said, or blank until the first update,
		// so a row does not jump in height the moment the panel is opened.
		String before = lastSaid.get(golem);
		JLabel place = new JLabel(before == null ? " " : before);
		place.setFont(FontManager.getRunescapeSmallFont());
		place.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		place.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 0));
		place.setMinimumSize(SQUEEZED);

		// The dimmed prompt is the name the plugin would call it, so an unnamed golem still reads
		// as somebody. Left as a prompt rather than put in the field: it is not a name until it
		// is typed, and a field holding it would save it as one.
		String suggested = names.suggested(golem);
		PlaceholderField name = new PlaceholderField(golem.getNickname(),
			suggested == null ? "Unnamed golem" : suggested);
		name.setMinimumSize(SQUEEZED);
		// A field holding more than it can show scrolls to the caret, and a fresh one leaves that
		// at the end: a golem with a long name showed the last of it with the first off the left
		// edge. The row is built with the name already in it, so this is where it is wound back.
		name.setCaretPosition(0);
		Row[] self = new Row[1];
		name.nameTip();
		name.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover(name);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				leave(name);
			}
		});
		name.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rename();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rename();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rename();
			}

			private void rename()
			{
				// Typed or put in from the game, the tip says the name the field now holds.
				name.nameTip();
				if (self[0] != null && self[0].settingName)
				{
					return;
				}
				// Not set here: golems belong to the client thread, and this is Swing's.
				String text = name.getText().trim();
				onRename.accept(golem, text.isEmpty() ? null : text);
			}
		});
		// Name across the top, place beneath it.
		JButton find = new JButton(TARGET);
		find.setToolTipText("Point at this golem until you reach it");
		find.setFocusPainted(false);
		find.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		find.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
		find.addActionListener(e -> onFind.accept(golem));

		JButton page = new JButton(INFO);
		page.setToolTipText("This golem's page");
		page.setFocusPainted(false);
		page.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		page.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
		page.addActionListener(e -> onOpen.accept(golem));

		JButton remove = new JButton(REMOVE);
		remove.setToolTipText("Remove this golem");
		remove.setFocusPainted(false);
		remove.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		remove.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
		remove.addActionListener(e -> onRemove.accept(golem));
		JPanel buttons = new JPanel(new BorderLayout(2, 0));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttons.add(page, BorderLayout.WEST);
		buttons.add(find, BorderLayout.CENTER);
		buttons.add(remove, BorderLayout.EAST);

		// In front of the name, where a star is looked for. Painted from the golem each time, so it
		// is right however the golem was starred.
		JButton star = new JButton(new Star(golem));
		star.setToolTipText("Star this golem to keep it at the top of the list");
		star.setFocusPainted(false);
		star.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		star.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 2));
		star.addActionListener(e -> onStar.accept(golem));

		// The name shares the top of the row with the buttons; the place has the whole width of
		// the row underneath, which is what it needs to say "Sailing to Port Khazard".
		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		top.setMinimumSize(SQUEEZED);
		top.add(star, BorderLayout.WEST);
		top.add(name, BorderLayout.CENTER);
		top.add(buttons, BorderLayout.EAST);

		JPanel text = new JPanel(new BorderLayout());
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		text.setMinimumSize(SQUEEZED);
		text.add(top, BorderLayout.CENTER);
		text.add(place, BorderLayout.SOUTH);
		panel.add(text, BorderLayout.CENTER);

		// Spacing between rows, which a BoxLayout will not do on its own.
		JPanel spaced = new JPanel(new BorderLayout());
		spaced.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spaced.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		spaced.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
		spaced.add(panel, BorderLayout.CENTER);
		self[0] = new Row(spaced, name);
		self[0].place = place;
		return self[0];
	}

	/** A star, filled in gold for a starred golem and drawn in outline for the rest. */
	private static final class Star implements Icon
	{
		private static final int SIZE = 14;
		private static final Color GOLD = new Color(0xFFB83F);

		private final Golem golem;

		private Star(Golem golem)
		{
			this.golem = golem;
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}

		@Override
		public void paintIcon(Component on, Graphics g, int x, int y)
		{
			Graphics2D drawing = (Graphics2D) g.create();
			drawing.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			drawing.translate(x, y);
			Path2D.Float shape = new Path2D.Float();
			float middle = SIZE / 2f;
			for (int point = 0; point < 10; point++)
			{
				// Five points out and five in, from straight up.
				double angle = Math.PI * point / 5 - Math.PI / 2;
				float reach = point % 2 == 0 ? middle - 0.5f : middle * 0.42f;
				float px = middle + (float) Math.cos(angle) * reach;
				float py = middle + 0.6f + (float) Math.sin(angle) * reach;
				if (point == 0)
				{
					shape.moveTo(px, py);
				}
				else
				{
					shape.lineTo(px, py);
				}
			}
			shape.closePath();
			if (golem.isFavourite())
			{
				drawing.setColor(GOLD);
				drawing.fill(shape);
			}
			else
			{
				drawing.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				drawing.setStroke(new BasicStroke(1.2f));
				drawing.draw(shape);
			}
			drawing.dispose();
		}
	}

	/**
	 * The list, kept to the width of what it is scrolled in.
	 *
	 * <p>A panel in a scroll pane is laid out at whatever width its contents ask for, and a row
	 * asks for room enough for a long name and a long place. The list then ran on past the right
	 * edge of the viewport, taking the buttons at the end of each row with it and leaving them
	 * under the scrollbar. Tracking the viewport's width squeezes the text instead, which is what
	 * a text field and a label are for.
	 */
	private static final class Tracking extends JPanel implements Scrollable
	{
		private Tracking()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return visible.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/**
	 * A text field that shows dimmed italic prompt text while it is empty. Swing has no
	 * placeholder, and the obvious workaround — real text cleared on focus — is wrong here:
	 * that text is indistinguishable from a name and would be saved as one.
	 */
	private static final class PlaceholderField extends JTextField
	{
		private final String prompt;

		/** How far the prompt is scrolled to the left, while a prompt too long for the field is hovered. */
		private int promptOffset;

		PlaceholderField(String initial)
		{
			this(initial, "Unnamed golem");
		}

		PlaceholderField(String initial, String prompt)
		{
			super(initial == null ? "" : initial);
			this.prompt = prompt;
			setFont(ROW);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setForeground(Color.WHITE);
			setCaretColor(Color.WHITE);
			setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		}

		/**
		 * Says the whole name, typed or prompted, when a golem's name field is hovered. The field is
		 * kept narrow so the buttons beside it have their room, and a long name is cut off in it.
		 */
		void nameTip()
		{
			String typed = getText().trim();
			String shown = (typed.isEmpty() ? prompt : typed)
				.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
			setToolTipText("<html>" + shown + "<br>" + (typed.isEmpty() ? "Click to name" : "Click to rename")
				+ "</html>");
		}

		/** How many pixels of what the field shows, its text or else its prompt, do not fit in it. */
		int overflow()
		{
			if (getText().isEmpty())
			{
				Insets edges = getInsets();
				return getFontMetrics(PLACEHOLDER).stringWidth(prompt) - (getWidth() - edges.left - edges.right);
			}
			BoundedRangeModel visible = getHorizontalVisibility();
			return visible.getMaximum() - visible.getExtent();
		}

		/**
		 * Shows what the field holds from this many pixels in. Text is scrolled the way the field
		 * scrolls it for the caret; the prompt is painted here and not by the field, so it is moved
		 * along by hand.
		 */
		void scrollTo(int offset)
		{
			if (!getText().isEmpty())
			{
				setScrollOffset(offset);
			}
			else if (promptOffset != offset)
			{
				promptOffset = offset;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);

			if (!getText().isEmpty())
			{
				return;
			}

			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g2.setFont(PLACEHOLDER);
				g2.setColor(PLACEHOLDER_COLOUR);

				// On the same baseline as the real text, so the prompt does not jump
				// when the player types over it.
				Insets edges = getInsets();
				int x = edges.left - promptOffset;
				int y = (getHeight() - g2.getFontMetrics().getHeight()) / 2
					+ g2.getFontMetrics().getAscent();
				// Kept inside the border, as the field keeps its own text, so a prompt scrolled
				// left does not run out over the edge.
				g2.clipRect(edges.left, 0, getWidth() - edges.left - edges.right, getHeight());
				g2.drawString(prompt, x, y);
			}
			finally
			{
				g2.dispose();
			}
		}
	}
}
