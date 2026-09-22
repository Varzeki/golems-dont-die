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

	private final JFrame frame = new JFrame("Golem");
	private final JLabel title = new JLabel();
	private final JLabel place = new JLabel();
	private final JPanel traits = new JPanel();
	private final JButton find = new JButton("Find");

	/** Points the arrow at the golem, on the client thread. */
	private final Consumer<Golem> onFind;

	/**
	 * The golem the page is showing, or null when it has never been opened. Set on the Swing
	 * thread and read on the client thread, which works out where the golem is.
	 */
	private volatile Golem showing;

	GolemPage(Consumer<Golem> onFind)
	{
		this.onFind = onFind;

		title.setFont(TITLE);
		title.setForeground(Color.WHITE);
		place.setFont(BODY);
		place.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel heading = new JPanel();
		heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
		heading.setBackground(ColorScheme.DARK_GRAY_COLOR);
		heading.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		place.setAlignmentX(Component.LEFT_ALIGNMENT);
		heading.add(title);
		heading.add(Box.createVerticalStrut(4));
		heading.add(place);

		traits.setLayout(new BoxLayout(traits, BoxLayout.Y_AXIS));
		traits.setBackground(ColorScheme.DARK_GRAY_COLOR);
		traits.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));

		JScrollPane scroll = new JScrollPane(traits);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		// Never sideways: the lines wrap to the window, so there is nothing off to the right.
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

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
		body.add(scroll, BorderLayout.CENTER);
		body.add(buttons, BorderLayout.SOUTH);

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
		title.setText(golem.getNickname() == null ? "Unnamed golem" : golem.getNickname());
		place.setText(" ");
		find.setEnabled(true);
		listTraits(golem);

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
			label.setAlignmentX(Component.LEFT_ALIGNMENT);

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
			line.setAlignmentX(Component.LEFT_ALIGNMENT);
			line.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

			traits.add(label);
			traits.add(line);
		}
		traits.revalidate();
		traits.repaint();
	}

}
