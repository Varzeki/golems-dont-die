package com.golemsdontdie;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

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

	private final JPanel rows = new JPanel();
	private final JLabel summary = new JLabel();

	/**
	 * Offers to make up the difference when fewer golems are roaming than have been crafted.
	 * Hidden whenever the two agree.
	 */
	private final JButton revive = new JButton();

	private final Consumer<Golem> onRemove;

	/** A golem and its new name, or null for none. Called on the Swing thread. */
	private final java.util.function.BiConsumer<Golem, String> onRename;

	private final Runnable onRevive;

	/**
	 * The row showing each golem, keyed by the golem itself: one restored at login is a new
	 * object, and a row holding the old one would rename a golem no longer in the roster.
	 */
	private final java.util.Map<Golem, Row> shown = new java.util.IdentityHashMap<>();

	/** One golem's row: what goes in the list, and the name field in it. */
	private static final class Row
	{
		final JPanel component;
		final JTextField name;

		/** Set while the name is being put in from the game, so it is not taken for typing. */
		boolean settingName;

		Row(JPanel component, JTextField name)
		{
			this.component = component;
			this.name = name;
		}
	}

	GolemListPanel(Consumer<Golem> onRemove, java.util.function.BiConsumer<Golem, String> onRename, Runnable onRevive)
	{
		super(false);
		this.onRemove = onRemove;
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

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
		header.add(summary, BorderLayout.NORTH);
		header.add(revive, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Rows are pinned to the top of a filler panel so a short list does not stretch
		// down the whole panel.
		JPanel filler = new JPanel(new BorderLayout());
		filler.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filler.add(rows, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(filler,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
	}

	/**
	* Redraws the list. Rebuilt only when the set of golems changes: this is called every game
	* tick, and recreating a text field the player might be typing in would make renaming
	* impossible.
	*
	* @param missing how many fewer golems are roaming than crafted; the revive button shows
	*                only when this is positive
	*/
	void refresh(List<Golem> golems, int missing)
	{
		refresh(golems, missing, false);
	}

	/**
	 * Brings the list up to date with the roster, changing only what changed: rebuilding the
	 * whole list made three thousand rows of components every time a golem was made or
	 * crumbled, and threw away a half-typed name.
	 *
	 * @param names also show names given in game, which leave the roster otherwise unchanged
	 */
	void refresh(List<Golem> golems, int missing, boolean names)
	{
		SwingUtilities.invokeLater(() ->
		{
			updateRevive(missing);

			java.util.Set<Golem> wanted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			wanted.addAll(golems);
			boolean changed = false;

			// Gone first, so what is left in the list is only rows that are staying.
			java.util.Iterator<java.util.Map.Entry<Golem, Row>> it = shown.entrySet().iterator();
			while (it.hasNext())
			{
				java.util.Map.Entry<Golem, Row> entry = it.next();
				if (!wanted.contains(entry.getKey()))
				{
					rows.remove(entry.getValue().component);
					it.remove();
					changed = true;
				}
			}

			// Then walk the roster against what is displayed: a row in its place is left
			// alone, a new golem's is inserted, one out of place is moved.
			for (int i = 0; i < golems.size(); i++)
			{
				Golem golem = golems.get(i);
				Row row = shown.get(golem);
				if (row == null)
				{
					row = row(golem);
					shown.put(golem, row);
					rows.add(row.component, i);
					changed = true;
				}
				else
				{
					if (i >= rows.getComponentCount() || rows.getComponent(i) != row.component)
					{
						rows.remove(row.component);
						rows.add(row.component, Math.min(i, rows.getComponentCount()));
						changed = true;
					}
					if (names)
					{
						showName(row, golem);
					}
				}
			}

			updateSummary(golems.size());
			if (changed)
			{
				rows.revalidate();
				rows.repaint();
			}
		});
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
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

		JTextField name = new PlaceholderField(golem.getNickname());
		Row[] self = new Row[1];
		name.setToolTipText("Name this golem");
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
				if (self[0] != null && self[0].settingName)
				{
					return;
				}
				// Not set here: golems belong to the client thread, and this is Swing's.
				String text = name.getText().trim();
				onRename.accept(golem, text.isEmpty() ? null : text);
			}
		});
		panel.add(name, BorderLayout.CENTER);

		JButton remove = new JButton("✕");
		remove.setToolTipText("Remove this golem");
		remove.setFont(ROW);
		remove.setFocusPainted(false);
		remove.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		remove.setForeground(Color.LIGHT_GRAY);
		remove.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
		remove.addActionListener(e -> onRemove.accept(golem));
		panel.add(remove, BorderLayout.EAST);

		// Spacing between rows, which a BoxLayout will not do on its own.
		JPanel spaced = new JPanel(new BorderLayout());
		spaced.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spaced.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		spaced.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
		spaced.add(panel, BorderLayout.CENTER);
		self[0] = new Row(spaced, name);
		return self[0];
	}

	/**
	 * A text field that shows dimmed italic prompt text while it is empty. Swing has no
	 * placeholder, and the obvious workaround — real text cleared on focus — is wrong here:
	 * that text is indistinguishable from a name and would be saved as one.
	 */
	private static final class PlaceholderField extends JTextField
	{
		private static final String PROMPT = "Unnamed Golem";

		PlaceholderField(String initial)
		{
			super(initial == null ? "" : initial);
			setFont(ROW);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setForeground(Color.WHITE);
			setCaretColor(Color.WHITE);
			setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
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
				int x = getInsets().left;
				int y = (getHeight() - g2.getFontMetrics().getHeight()) / 2
					+ g2.getFontMetrics().getAscent();
				g2.drawString(PROMPT, x, y);
			}
			finally
			{
				g2.dispose();
			}
		}
	}
}
