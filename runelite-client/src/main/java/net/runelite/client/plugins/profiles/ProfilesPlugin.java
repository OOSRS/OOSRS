package net.runelite.client.plugins.profiles;

import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.openosrs.client.accounts.AccountLoginCoordinator;
import net.openosrs.client.accounts.AccountProfileService;
import net.openosrs.client.accounts.JagexAuthService;
import net.runelite.api.events.ClientTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(name = "Profiles", description = "Save Jagex accounts and choose a character at the login screen",
	tags = {"openosrs", "jagex", "accounts", "login"}, enabledByDefault = true)
public final class ProfilesPlugin extends Plugin
{
	@Inject private ClientToolbar toolbar;
	@Inject private AccountProfileService profiles;
	@Inject private AccountLoginCoordinator login;
	@Inject private JagexAuthService auth;
	@Inject private ProfilesConfig config;
	private ProfilesPanel panel;
	private NavigationButton navigation;

	@Provides
	ProfilesConfig provideConfig(ConfigManager manager) { return manager.getConfig(ProfilesConfig.class); }

	@Override
	protected void startUp() throws Exception
	{
		Runnable start = () ->
		{
			panel = new ProfilesPanel(profiles, login, auth, config);
			navigation = NavigationButton.builder().tooltip("Profiles").priority(4).icon(icon()).panel(panel).build();
			toolbar.addNavigation(navigation);
			panel.refresh();
		};
		if (SwingUtilities.isEventDispatchThread()) start.run(); else SwingUtilities.invokeAndWait(start);
	}

	@Override
	protected void shutDown() throws Exception
	{
		auth.cancel();
		login.clearSelection();
		Runnable stop = () ->
		{
			if (panel != null) panel.close();
			if (navigation != null) toolbar.removeNavigation(navigation);
			panel = null; navigation = null;
		};
		if (SwingUtilities.isEventDispatchThread()) stop.run(); else SwingUtilities.invokeAndWait(stop);
	}

	@Subscribe
	public void onClientTick(ClientTick event) { login.tick(); }

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("openosrsprofiles".equals(event.getGroup())) SwingUtilities.invokeLater(() -> { if (panel != null) panel.redraw(); });
	}

	private static BufferedImage icon()
	{
		BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(71, 163, 255));
		g.setStroke(new BasicStroke(1.7f));
		g.drawOval(7, 2, 6, 6);
		g.drawArc(3, 10, 14, 14, 0, 180);
		g.dispose();
		return image;
	}
}
