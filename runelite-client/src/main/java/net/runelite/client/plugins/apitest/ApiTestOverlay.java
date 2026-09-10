package net.runelite.client.plugins.apitest;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

public class ApiTestOverlay extends OverlayPanel
{
	private final Client client;
	private final ApiTestPlugin plugin;

	@Inject
	private ApiTestOverlay(Client client, ApiTestPlugin plugin)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player me = client.getLocalPlayer();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("API Test")
			.leftColor(Color.GREEN)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left(me != null ? me.getName() : "not logged in")
			.right("tick " + client.getTickCount())
			.build());

		String status = plugin.getLastStatus();
		if (status.length() > 60)
		{
			status = status.substring(0, 60) + "...";
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left(status)
			.build());

		return super.render(graphics);
	}
}
