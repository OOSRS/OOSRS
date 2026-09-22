/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.motion.MouseProfile;
import net.openosrs.api.input.motion.MouseProfileStore;
import net.openosrs.api.input.motion.ProfileScorer;
import net.openosrs.api.input.motion.ProfileScorer.CategoryStats;
import net.openosrs.api.input.motion.ProfileScorer.Context;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ColorScheme;

/**
 * Dedicated standalone popup window for mouse behavior analytics, real-time naturalness scoring,
 * velocity and acceleration dynamics visualizer, and 2D spatial density heatmaps.
 */
@Slf4j
@Singleton
public class LearningDashboardFrame extends JFrame
{
	private final ProfileScorer scorer;
	private final InputSettings settings;
	private final MouseProfileStore profileStore;
	private final CursorState state;

	private final JLabel naturalnessLabel = new JLabel("No samples", SwingConstants.CENTER);
	private final JProgressBar naturalnessBar = new JProgressBar(0, 100);
	private final JLabel ratingLabel = new JLabel("CALIBRATING", SwingConstants.CENTER);
	private final JLabel sampleCountLabel = new JLabel("Total Trajectories: 0");
	private final JLabel modeStatusLabel = new JLabel("Mode: Active Learning");

	private final DefaultTableModel categoryTableModel;
	private final VelocityGraphPanel velocityGraphPanel = new VelocityGraphPanel();
	private final HeatmapPanel heatmapPanel = new HeatmapPanel();

	private final Timer refreshTimer;

	@Inject
	public LearningDashboardFrame(ProfileScorer scorer, InputSettings settings,
		MouseProfileStore profileStore, CursorState state)
	{
		this.scorer = scorer;
		this.settings = settings;
		this.profileStore = profileStore;
		this.state = state;

		setTitle("Human Mouse Analytics & Learning Engine");
		setIconImage(ClientUI.ICON);
		setSize(680, 540);
		setMinimumSize(new Dimension(560, 440));
		setLocationRelativeTo(null);
		setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout(8, 8));

		// --- Header Panel ---
		JPanel headerPanel = new JPanel(new BorderLayout(8, 8));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

		JPanel topTitlePanel = new JPanel(new GridLayout(2, 1, 4, 4));
		topTitlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel titleLabel = new JLabel("MOUSE MOVEMENT LEARNING");
		titleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
		titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
		topTitlePanel.add(titleLabel);
		modeStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		topTitlePanel.add(modeStatusLabel);
		headerPanel.add(topTitlePanel, BorderLayout.WEST);

		JPanel scoreCard = new JPanel(new GridLayout(3, 1, 2, 2));
		scoreCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scoreCard.setPreferredSize(new Dimension(200, 55));
		naturalnessLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
		naturalnessLabel.setForeground(Color.GREEN);
		naturalnessLabel.setToolTipText("Heuristic motion score; not a probability or detection assessment.");
		scoreCard.add(naturalnessLabel);

		naturalnessBar.setValue(0);
		naturalnessBar.setForeground(new Color(60, 200, 80));
		naturalnessBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		naturalnessBar.setBorderPainted(false);
		scoreCard.add(naturalnessBar);

		ratingLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
		ratingLabel.setForeground(Color.WHITE);
		scoreCard.add(ratingLabel);
		headerPanel.add(scoreCard, BorderLayout.EAST);

		add(headerPanel, BorderLayout.NORTH);

		// --- Center Tabbed Content ---
		JTabbedPane tabbedPane = new JTabbedPane();
		tabbedPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);

		// Movement statistics and velocity graph.
		JPanel dynamicsTab = new JPanel(new BorderLayout(8, 8));
		dynamicsTab.setBackground(ColorScheme.DARK_GRAY_COLOR);
		dynamicsTab.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		dynamicsTab.add(velocityGraphPanel, BorderLayout.CENTER);

		JPanel dynamicsInfo = new JPanel(new GridLayout(1, 2, 8, 8));
		dynamicsInfo.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sampleCountLabel.setForeground(Color.WHITE);
		dynamicsInfo.add(sampleCountLabel);

		JLabel fittsLabel = new JLabel("Saved movement timing is used by the active planner");
		fittsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		dynamicsInfo.add(fittsLabel);
		dynamicsTab.add(dynamicsInfo, BorderLayout.SOUTH);

		tabbedPane.addTab("Velocity & Fitts Dynamics", dynamicsTab);

		// Per-category breakdown.
		String[] columnNames = {"Context", "Samples", "Avg Speed (px/s)", "Curvature", "Jitter (px)", "Motion score"};
		categoryTableModel = new DefaultTableModel(columnNames, 0)
		{
			@Override
			public boolean isCellEditable(int row, int column) { return false; }
		};
		JTable categoryTable = new JTable(categoryTableModel);
		categoryTable.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		categoryTable.setForeground(Color.WHITE);
		categoryTable.setGridColor(ColorScheme.DARK_GRAY_COLOR);
		categoryTable.setRowHeight(26);
		DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
		centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
		for (int i = 0; i < categoryTable.getColumnCount(); i++)
		{
			categoryTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
		}

		JScrollPane tableScroll = new JScrollPane(categoryTable);
		tableScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabbedPane.addTab("Category Breakdown", tableScroll);

		// Click density heatmap.
		tabbedPane.addTab("Spatial Dwell Heatmap", heatmapPanel);

		add(tabbedPane, BorderLayout.CENTER);

		// --- Footer Buttons ---
		JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
		footerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton saveButton = new JButton("Save Learned Profile");
		saveButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		saveButton.setForeground(Color.WHITE);
		saveButton.addActionListener(e -> saveCurrentProfile());
		footerPanel.add(saveButton);

		JButton closeButton = new JButton("Close");
		closeButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		closeButton.setForeground(Color.WHITE);
		closeButton.addActionListener(e -> close());
		footerPanel.add(closeButton);

		add(footerPanel, BorderLayout.SOUTH);

		// Auto-refresh timer when visible
		refreshTimer = new Timer(400, e -> updateDisplay());

		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				close();
			}
		});
	}

	public void open()
	{
		setVisible(true);
		toFront();
		refreshTimer.start();
		updateDisplay();
	}

	public void close()
	{
		refreshTimer.stop();
		setVisible(false);
	}

	public void updateDisplay()
	{
		if (!isVisible())
		{
			return;
		}

		double naturalness = scorer.getOverallNaturalness();
		naturalnessLabel.setText(scorer.getTotalTrajectoriesScored() == 0 ? "No samples" : String.format("%.1f/100", naturalness));
		naturalnessBar.setValue((int) Math.round(naturalness));

		if (naturalness >= 80.0)
		{
			naturalnessLabel.setForeground(new Color(60, 220, 80));
			naturalnessBar.setForeground(new Color(60, 220, 80));
			ratingLabel.setText("HIGH MOTION SCORE");
			ratingLabel.setForeground(new Color(60, 220, 80));
		}
		else if (naturalness >= 65.0)
		{
			naturalnessLabel.setForeground(new Color(240, 210, 50));
			naturalnessBar.setForeground(new Color(240, 210, 50));
			ratingLabel.setText("MID MOTION SCORE");
			ratingLabel.setForeground(new Color(240, 210, 50));
		}
		else
		{
			naturalnessLabel.setForeground(new Color(240, 70, 60));
			naturalnessBar.setForeground(new Color(240, 70, 60));
			ratingLabel.setText("LOW MOTION SCORE");
			ratingLabel.setForeground(new Color(240, 70, 60));
		}

		if (scorer.getTotalTrajectoriesScored() == 0) ratingLabel.setText("NO SAMPLES");

		boolean learning = settings.isLearnMode();
		modeStatusLabel.setText("Status: " + (learning ? "Learning from Gameplay (Active)" : "Standby (Paused)"));
		sampleCountLabel.setText("Total Trajectories Scored: " + scorer.getTotalTrajectoriesScored());

		// Update Table
		categoryTableModel.setRowCount(0);
		Map<Context, CategoryStats> metrics = scorer.getAllCategoryMetrics();
		for (Context ctx : Context.values())
		{
			CategoryStats s = metrics.get(ctx);
			if (s != null)
			{
				categoryTableModel.addRow(new Object[]{
					ctx.name(),
					s.getSamples(),
					String.format("%.0f", s.getAvgSpeed()),
					String.format("%.3f", s.getAvgCurvature()),
					String.format("%.2f", s.getAvgJitter()),
					String.format("%.1f/100", s.getAvgNaturalness())
				});
			}
		}

		// Repaint graph and heatmap
		velocityGraphPanel.setVelocities(scorer.getRecentVelocities());
		heatmapPanel.setHeatmap(scorer.getSpatialHeatmap());
	}

	private void saveCurrentProfile()
	{
		String profileName = settings.getProfileName();
		try
		{
			scorer.saveProfileData(Paths.get(System.getProperty("user.home"), ".openosrs", "mouse-profiles"), profileName);
			MouseProfile prof = profileStore.load(profileName);
			scorer.adaptProfile(prof);
			profileStore.save(prof);
			JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' saved; timing adapts when enough movement samples are available.",
				"Profile Saved", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception e)
		{
			log.error("Failed to save learned mouse profile", e);
			JOptionPane.showMessageDialog(this, "Could not save profile: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static class VelocityGraphPanel extends JPanel
	{
		private List<Double> velocities = null;

		VelocityGraphPanel()
		{
			setBackground(new Color(25, 25, 25));
			setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR),
				"Real-Time Velocity & Deceleration Profile (px/s)",
				0, 0, new Font("SansSerif", Font.PLAIN, 12), Color.WHITE));
		}

		void setVelocities(List<Double> vels)
		{
			this.velocities = vels;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int w = getWidth() - 32;
			int h = getHeight() - 48;
			int x0 = 16;
			int y0 = 32;

			if (w <= 10 || h <= 10)
			{
				return;
			}

			// Draw grid
			g2.setColor(new Color(45, 45, 45));
			for (int i = 1; i <= 4; i++)
			{
				int gy = y0 + (h * i) / 5;
				g2.drawLine(x0, gy, x0 + w, gy);
			}

			if (velocities == null || velocities.isEmpty())
			{
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g2.drawString("Awaiting movement trajectory...", x0 + w / 2 - 80, y0 + h / 2);
				return;
			}

			double maxV = 150.0;
			for (double v : velocities)
			{
				if (v > maxV) maxV = v;
			}

			int n = velocities.size();
			int prevX = x0;
			int prevY = y0 + h;

			g2.setColor(ColorScheme.BRAND_ORANGE);
			for (int i = 0; i < n; i++)
			{
				int curX = x0 + (int) Math.round((i / (double) Math.max(1, n - 1)) * w);
				int curY = y0 + h - (int) Math.round((velocities.get(i) / maxV) * h);

				if (i > 0)
				{
					g2.drawLine(prevX, prevY, curX, curY);
				}
				prevX = curX;
				prevY = curY;
			}

			// Draw peak velocity label
			g2.setColor(Color.WHITE);
			g2.drawString(String.format("Peak: %.0f px/s", maxV), x0 + 8, y0 + 16);
		}
	}

	private static class HeatmapPanel extends JPanel
	{
		private int[][] heatmap = null;

		HeatmapPanel()
		{
			setBackground(new Color(20, 20, 20));
		}

		void setHeatmap(int[][] heatmap)
		{
			this.heatmap = heatmap;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (heatmap == null)
			{
				return;
			}

			int rows = heatmap.length;
			int cols = heatmap[0].length;
			int maxVal = 1;
			for (int[] row : heatmap)
			{
				for (int v : row)
				{
					if (v > maxVal) maxVal = v;
				}
			}

			int w = getWidth() - 32;
			int h = getHeight() - 32;
			int cellW = Math.max(1, w / cols);
			int cellH = Math.max(1, h / rows);

			for (int r = 0; r < rows; r++)
			{
				for (int c = 0; c < cols; c++)
				{
					int val = heatmap[r][c];
					if (val > 0)
					{
						float ratio = Math.min(1.0f, (float) val / maxVal);
						Color heatColor = new Color(
							Math.min(1.0f, ratio * 2.0f),
							Math.max(0.0f, 1.0f - ratio * 1.5f),
							Math.max(0.0f, 0.8f - ratio),
							0.4f + ratio * 0.5f
						);
						g.setColor(heatColor);
						g.fillRect(16 + c * cellW, 16 + r * cellH, cellW, cellH);
					}
				}
			}
		}
	}
}
