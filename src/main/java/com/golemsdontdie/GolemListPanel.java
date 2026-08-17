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
 * <p>A panel rather than config items because the list is not a setting: it grows and
 * shrinks as golems are made and retired, and RuneLite's config UI is generated from a
 * fixed interface. A dynamic list with per-row controls has to be built by hand.
 *
 * <p>It replaces what used to be a "forget saved golems" tick box — an all-or-nothing
 * button that could only ever delete every golem the player had, which is a poor
 * answer to wanting rid of one.
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
	 * Offers to make up the difference when fewer golems are roaming than have been
	 * crafted. Hidden whenever the two agree, which is the normal state.
	 */
	private final JButton revive = new JButton();

	/** Called with the golem to remove when its X is clicked. */
	private final Consumer<Golem> onRemove;

	/** Called when a golem is renamed, so the roster can be saved. */
	private final Runnable onRename;

	/** Called when the revive button is pressed. */
	private final Runnable onRevive;

	/** What is currently displayed, so a rebuild can be skipped when nothing changed. */
	private final List<Long> shown = new ArrayList<>();

	GolemListPanel(Consumer<Golem> onRemove, Runnable onRename, Runnable onRevive)
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
		// its entries down the whole panel.
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
	 * Redraws the list.
	 *
	 * <p>Rebuilt only when the set of golems actually changes. This is called every
	 * game tick, and tearing down and recreating a text field the player might be
	 * typing in would make renaming impossible.
	 */
	/**
	 * Redraws the list.
	 *
	 * @param missing how many fewer golems are roaming than have been crafted; the
	 *                revive button shows only when this is positive
	 */
	void refresh(List<Golem> golems, int missing)
	{
		SwingUtilities.invokeLater(() ->
		{
			updateRevive(missing);

			List<Long> ids = new ArrayList<>(golems.size());
			for (Golem golem : golems)
			{
				ids.add(golem.getId());
			}
			if (ids.equals(shown))
			{
				updateSummary(golems.size());
				return;
			}

			shown.clear();
			shown.addAll(ids);
			rows.removeAll();

			for (Golem golem : golems)
			{
				rows.add(row(golem));
			}

			updateSummary(golems.size());
			rows.revalidate();
			rows.repaint();
		});
	}

	/**
	 * Shows or hides the revive button.
	 *
	 * <p>Only present when there is something to revive, so in ordinary play it is not
	 * there at all — a button that does nothing most of the time is worse than no
	 * button.
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

	private JPanel row(Golem golem)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 6));
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

		JTextField name = new PlaceholderField(golem.getNickname());
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
				String text = name.getText().trim();
				golem.setNickname(text.isEmpty() ? null : text);
				onRename.run();
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
		return spaced;
	}

	/**
	 * A text field that shows dimmed italic prompt text while it is empty.
	 *
	 * <p>Swing has no placeholder of its own, and the obvious workaround — putting the
	 * prompt in as real text and clearing it on focus — is wrong here: that text would
	 * be indistinguishable from a name, and would be saved as one the moment anything
	 * else read the field. Drawing it instead means the field is genuinely empty until
	 * the player types.
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

				// Sat on the same baseline the real text uses, so the prompt does not
				// jump when the player starts typing over it.
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
