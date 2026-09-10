/*
 * OpenOSRS restoration - interactive API test sidebar panel.
 * Pure Swing layer: builds the sections and buttons, shows status/results.
 * All game-state work is delegated to ApiPanelPlugin; this class never
 * touches the client or the API services directly.
 */
package net.runelite.client.plugins.apitest;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.PluginPanel;

class ApiPanelPanel extends PluginPanel
{
	/** Result log is capped so a long session cannot grow it without bound. */
	private static final int RESULT_MAX_CHARS = 8000;
	private static final int RESULT_TRIM_CHARS = 6000;
	private static final int RESULT_AREA_HEIGHT = 220;

	private static final DateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

	private final ApiPanelPlugin plugin;

	private final JLabel statusLabel = new JLabel("idle - pick an action");
	private final JTextArea resultArea = new JTextArea();
	private final JCheckBox allowLiveSend = new JCheckBox("Confirm: enable live packet send");
	private final JButton sendPacketButton = new JButton("Send RESUME_PAUSEBUTTON");

	@Inject
	private ApiPanelPanel(ApiPanelPlugin plugin)
	{
		this.plugin = plugin;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("API Panel");
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		title.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(title);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
		add(statusLabel);

		JPanel npcSection = section("NPC");
		addButton(npcSection, "Nearest NPC info", () -> plugin.nearestNpcInfo());
		addButton(npcSection, "Attack nearest attackable", () -> plugin.attackNearestAttackable());
		add(npcSection);

		JPanel inventorySection = section("Inventory");
		addButton(inventorySection, "Inventory contents", () -> plugin.inventoryContents());
		addButton(inventorySection, "Count food", () -> plugin.countFood());
		add(inventorySection);

		JPanel bankSection = section("Bank");
		addButton(bankSection, "Is bank open?", () -> plugin.bankIsOpen());
		addButton(bankSection, "Open bank", () -> plugin.openBank());
		addButton(bankSection, "Close bank", () -> plugin.closeBank());
		add(bankSection);

		JPanel prayerSection = section("Prayer");
		addButton(prayerSection, "Quick prayer ON/OFF", () -> plugin.toggleQuickPrayer());
		addButton(prayerSection, "Prayer status", () -> plugin.prayerStatus());
		add(prayerSection);

		JPanel movementSection = section("Movement");
		addButton(movementSection, "My position", () -> plugin.myPosition());
		addButton(movementSection, "Destination", () -> plugin.destination());
		addButton(movementSection, "Run energy", () -> plugin.runEnergy());
		add(movementSection);

		JPanel teleportsSection = section("Teleports");
		addButton(teleportsSection, "Can I teleport?", () -> plugin.canITeleport());
		addButton(teleportsSection, "List teleports", () -> plugin.listTeleports());
		add(teleportsSection);

		JPanel packetsSection = section("Packets");
		allowLiveSend.setOpaque(false);
		allowLiveSend.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		allowLiveSend.addActionListener(this::onAllowLiveSendChanged);
		packetsSection.add(allowLiveSend);

		sendPacketButton.setEnabled(false);
		sendPacketButton.addActionListener(e -> plugin.sendResumePausebutton());
		packetsSection.add(sendPacketButton);
		add(packetsSection);

		JLabel resultTitle = new JLabel("Results");
		resultTitle.setFont(resultTitle.getFont().deriveFont(Font.BOLD));
		resultTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		resultTitle.setBorder(new EmptyBorder(8, 0, 4, 0));

		resultArea.setEditable(false);
		resultArea.setLineWrap(true);
		resultArea.setWrapStyleWord(true);
		JScrollPane resultScroll = new JScrollPane(resultArea);
		resultScroll.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, RESULT_AREA_HEIGHT));

		add(resultTitle);
		add(resultScroll);
	}

	/* ------------------------------------------------------------------ */
	/* EDT-only surface used by the plugin                                 */
	/* ------------------------------------------------------------------ */

	/** Update the one-line action status. Must be called on the EDT. */
	void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	/**
	 * Append one timestamped evidence line to the result area and keep the
	 * view scrolled to the newest entry. Must be called on the EDT.
	 */
	void appendResult(String line)
	{
		resultArea.append("[" + TIME_FORMAT.format(new Date()) + "] " + line + "\n");
		String text = resultArea.getText();
		if (text.length() > RESULT_MAX_CHARS)
		{
			resultArea.setText(text.substring(text.length() - RESULT_TRIM_CHARS));
			resultArea.setCaretPosition(resultArea.getDocument().getLength());
		}
	}

	/** True only when the user ticked the live-send confirmation checkbox. */
	boolean packetSendArmed()
	{
		return allowLiveSend.isSelected();
	}

	/* ------------------------------------------------------------------ */
	/* helpers                                                             */
	/* ------------------------------------------------------------------ */

	private void onAllowLiveSendChanged(ActionEvent event)
	{
		boolean armed = allowLiveSend.isSelected();
		sendPacketButton.setEnabled(armed);
		if (!armed)
		{
			setStatus("live packet send disarmed");
		}
		plugin.onSendArmChanged(armed);
	}

	private JPanel section(final String title)
	{
		JPanel box = new JPanel();
		box.setLayout(new DynamicGridLayout(0, 1, 0, 3));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createCompoundBorder(
			new EmptyBorder(4, 0, 4, 0), new EmptyBorder(6, 6, 6, 6)));

		JLabel label = new JLabel(title);
		label.setFont(label.getFont().deriveFont(Font.BOLD));
		label.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		box.add(label);
		return box;
	}

	private void addButton(JPanel section, final String label, final Runnable action)
	{
		JButton button = new JButton(label);
		button.addActionListener(e -> action.run());
		section.add(button);
	}
}
