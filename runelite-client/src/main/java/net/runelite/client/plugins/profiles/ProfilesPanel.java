package net.runelite.client.plugins.profiles;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.openosrs.client.accounts.AccountLoginCoordinator;
import net.openosrs.client.accounts.AccountProfileService;
import net.openosrs.client.accounts.JagexAuthService;
import net.openosrs.client.accounts.ProfileException;
import net.runelite.client.ui.PluginPanel;

/** Native Swing version of the approved Profiles sidebar. No credentials enter client config. */
final class ProfilesPanel extends PluginPanel
{
	private static final Color BACKGROUND = new Color(28, 36, 46);
	private static final Color CARD = new Color(36, 46, 58);
	private static final Color BLUE = new Color(36, 140, 237);
	private static final Color TEXT = new Color(229, 235, 242);
	private static final Color MUTED = new Color(159, 174, 191);
	private static final Font UI_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private final AccountProfileService profiles;
	private final AccountLoginCoordinator login;
	private final JagexAuthService auth;
	private final ProfilesConfig config;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
	{
		Thread thread = new Thread(task, "openosrs-profiles"); thread.setDaemon(true); return thread;
	});
	private final JTextField search = new JTextField();
	private final JPanel content = vertical();
	private final JTextArea status = text("");
	private final JTextArea storage = text("");
	private final JButton add = button("+  Add Jagex account", true, () -> startAuth(null));
	private final JButton launch = button("Log in", true, this::login);
	private final Timer timer;
	private final List<JButton> selectionButtons = new ArrayList<>();
	private List<AccountProfileService.ProfileView> accounts = List.of();
	private JagexAuthService.Attempt attempt;
	private JagexAuthService.Result result;
	private String reconnectId;
	private String notice;
	private boolean busy;
	private boolean locked;
	private boolean closed;
	private long epoch;

	ProfilesPanel(AccountProfileService profiles, AccountLoginCoordinator login, JagexAuthService auth, ProfilesConfig config)
	{
		this.profiles = profiles; this.login = login; this.auth = auth; this.config = config;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(BACKGROUND);
		setBorder(BorderFactory.createEmptyBorder(14, 9, 14, 9));
		JLabel brand = label("OPENOSRS", 10, MUTED); add(brand); gap(12);
		add(label("Profiles", 23, TEXT)); gap(5);
		add(text("Your accounts. One place.")); gap(16);
		add(add); gap(12);
		search.setToolTipText("Find a character or account");
		search.getAccessibleContext().setAccessibleName("Find a character or account");
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(CARD.brighter()), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		search.setBackground(CARD); search.setForeground(TEXT); search.setCaretColor(TEXT);
		search.setFont(UI_FONT);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e) { redraw(); }
			public void removeUpdate(DocumentEvent e) { redraw(); }
			public void changedUpdate(DocumentEvent e) { redraw(); }
		});
		add(search); gap(12); add(content); gap(12); add(status); gap(8); add(launch); gap(8);
		add(button("Clear selection", false, () -> { login.clearSelection(); notice = null; redraw(); }));
		gap(12); add(storage); gap(6);
		add(button("Refresh accounts", false, this::refresh));
		timer = new Timer(500, event -> updateState()); timer.start();
	}

	void refresh()
	{
		if (busy || closed) return;
		run("Loading accounts…", () -> {});
	}

	private void run(String message, Work action)
	{
		if (busy || closed) return;
		busy = true; notice = message; redraw();
		long operation = epoch;
		worker.execute(() ->
		{
			List<AccountProfileService.ProfileView> next = null;
			String error = null;
			try { action.run(); next = profiles.list(); }
			catch (Exception e) { error = message(e); }
			List<AccountProfileService.ProfileView> loaded = next;
			String failure = error;
			SwingUtilities.invokeLater(() ->
			{
				if (closed || operation != epoch) return;
				busy = false;
				if (loaded != null) { accounts = loaded; locked = false; }
				else if (failure != null && failure.startsWith("Saved accounts could not be unlocked")) locked = true;
				notice = failure;
				redraw();
			});
		});
	}

	void redraw()
	{
		if (closed) return;
		content.removeAll(); selectionButtons.clear();
		if (locked)
		{
			content.add(text("Your saved accounts could not be unlocked. Unlock the system key store and refresh, or continue without saving accounts."));
			content.add(button("Use session-only mode", false, () -> run("Opening temporary profiles…", profiles::useSessionOnly)));
		}
		else if (attempt != null && result == null) waiting();
		else if (result != null) chooseCharacters();
		else
		{
			String query = search.getText().trim().toLowerCase(Locale.ROOT);
			boolean any = false;
			for (AccountProfileService.ProfileView account : accounts)
			{
				List<AccountProfileService.CharacterView> matches = new ArrayList<>();
				for (AccountProfileService.CharacterView character : account.characters)
					if (account.label.toLowerCase(Locale.ROOT).contains(query) || character.name.toLowerCase(Locale.ROOT).contains(query)) matches.add(character);
				if (matches.isEmpty()) continue;
				any = true;
				JPanel group = vertical(); group.setBackground(CARD);
				group.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(CARD.brighter()), BorderFactory.createEmptyBorder(9, 8, 9, 8)));
				group.add(label(account.label, 12, TEXT));
				group.add(label(account.reconnect ? "Reconnect required" : "Jagex account", 10, account.reconnect ? new Color(255, 184, 112) : MUTED));
				group.add(Box.createVerticalStrut(7));
				for (AccountProfileService.CharacterView character : matches)
				{
					JPanel row = new JPanel(new BorderLayout(4, 0)); row.setOpaque(false);
					row.setBorder(BorderFactory.createEmptyBorder(config.compactRows() ? 2 : 5, 0, config.compactRows() ? 2 : 5, 0));
					JButton choose = button(character.name, false, () -> run("Preparing character…", () -> login.select(account.id, character.id).get()));
					choose.setHorizontalAlignment(JButton.LEFT);
					choose.setBackground(login.isSelected(account.id, character.id) ? BLUE.darker() : CARD);
					choose.setToolTipText(account.reconnect ? "Reconnect this account first" : "Select this character");
					choose.setEnabled(!account.reconnect && !busy && login.canSelect());
					if (!account.reconnect) selectionButtons.add(choose);
					row.add(choose, BorderLayout.CENTER);
					JButton star = button(character.favourite ? "★" : "☆", false,
						() -> run("Saving favourite…", () -> profiles.favourite(account.id, character.id, !character.favourite)));
					star.setToolTipText(character.favourite ? "Remove favourite" : "Favourite character");
					star.setEnabled(!busy); row.add(star, BorderLayout.EAST); group.add(row);
				}
				JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); actions.setOpaque(false);
				JButton reconnect = button("Reconnect", false, () -> startAuth(account.id)); reconnect.setEnabled(!busy); actions.add(reconnect);
				JButton edit = button("…", false, () -> editAccount(account)); edit.setToolTipText("Rename or remove account"); edit.setEnabled(!busy); actions.add(edit);
				group.add(actions); content.add(group); content.add(Box.createVerticalStrut(9));
			}
			if (!any) content.add(text(accounts.isEmpty() ? "Add a Jagex account to see its characters here. Your password stays in your browser." : "No matching characters."));
		}
		updateState(); content.revalidate(); content.repaint(); revalidate(); repaint();
	}

	private void updateState()
	{
		if (closed) return;
		status.setText(notice != null ? notice : login.message());
		storage.setText(locked ? "Saved accounts are locked; existing files are preserved." : profiles.isSessionOnly()
			? "Session only · accounts are forgotten when this client closes." : "Saved access stays encrypted on this device.");
		add.setEnabled(!busy && !locked && attempt == null && result == null);
		launch.setEnabled(!busy && !locked && attempt == null && result == null && login.canLogin());
		search.setEnabled(attempt == null && result == null);
		for (JButton select : selectionButtons) select.setEnabled(!busy && login.canSelect());
	}

	private void startAuth(String reconnect)
	{
		if (busy || closed) return;
		if (!confirm("Add Jagex account", "Sign in on Jagex's website in your browser. OpenOSRS receives a game session and character list; it never asks for your Jagex password.\n\nIf your browser cannot return automatically, paste its final localhost return link into the masked field here. Never share that link.", "Continue in browser")) return;
		busy = true; reconnectId = reconnect; notice = "Opening browser sign-in…"; redraw();
		long operation = ++epoch;
		worker.execute(() ->
		{
			try
			{
				JagexAuthService.Attempt started = auth.begin();
				SwingUtilities.invokeLater(() ->
				{
					if (closed || operation != epoch) { started.cancel(); return; }
					attempt = started; notice = "Complete sign-in in your browser."; redraw();
				});
				started.result().whenComplete((linked, error) -> SwingUtilities.invokeLater(() ->
				{
					if (closed || operation != epoch) return;
					busy = false; attempt = null; result = linked;
					notice = error == null ? "Choose the characters to save." : message(error);
					redraw();
				}));
				try { auth.openBrowser(started); }
				catch (ProfileException e) { SwingUtilities.invokeLater(() -> { if (!closed && operation == epoch && attempt != null) { notice = e.getMessage(); redraw(); } }); }
			}
			catch (Exception e) { SwingUtilities.invokeLater(() -> { if (!closed && operation == epoch) { busy = false; notice = message(e); redraw(); } }); }
		});
	}

	private void waiting()
	{
		content.add(text(attempt.needsManualReturn()
			? "After signing in, the browser may show a localhost connection error. Copy the complete address and paste it below. No root access is needed."
			: "Complete the Jagex sign-in in your browser. If it cannot return, paste its final localhost address below."));
		JPasswordField callback = new JPasswordField();
		callback.setFont(UI_FONT);
		callback.getAccessibleContext().setAccessibleName("Private sign-in return link");
		callback.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); content.add(callback);
		content.add(button("Complete sign-in", true, () ->
		{
			char[] value = callback.getPassword(); callback.setText("");
			clearMatchingClipboard(value);
			try { auth.completeManually(attempt, value); notice = "Verifying sign-in and loading characters…"; }
			catch (ProfileException e) { notice = e.getMessage(); }
			updateState();
		}));
		content.add(button("Copy sign-in link", false, () -> Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(attempt.loginUri().toString()), null)));
		content.add(button("Cancel", false, this::cancelAuth));
	}

	private void chooseCharacters()
	{
		content.add(text("Choose characters to keep in this client. Previously saved characters on this account stay selected."));
		Set<String> selected = new HashSet<>();
		for (JagexAuthService.CharacterInfo character : result.getCharacters())
		{
			selected.add(character.getId());
			JCheckBox check = new JCheckBox(character.getName(), true); check.putClientProperty("html.disable", true);
			check.setFont(UI_FONT);
			check.setOpaque(false); check.setForeground(TEXT); check.setEnabled(!busy);
			check.addActionListener(event -> { if (check.isSelected()) selected.add(character.getId()); else selected.remove(character.getId()); });
			content.add(check);
		}
		JButton save = button("Save selected characters", true, () ->
		{
			JagexAuthService.Result linked = result; String reconnect = reconnectId;
			if (selected.isEmpty()) { notice = "Choose at least one character."; updateState(); return; }
			Set<String> chosen = Set.copyOf(selected);
			run("Saving account…", () ->
			{
				profiles.save(linked, chosen, reconnect);
				SwingUtilities.invokeLater(() -> { if (!closed) { result = null; reconnectId = null; login.clearSelection(); } });
			});
		});
		save.setEnabled(!busy); content.add(save);
		JButton cancel = button("Cancel", false, this::cancelAuth);
		cancel.setEnabled(!busy); content.add(cancel);
	}

	private void cancelAuth()
	{
		++epoch; auth.cancel(); attempt = null; result = null; reconnectId = null; busy = false;
		notice = "Sign-in cancelled. Saved accounts were kept."; redraw();
	}

	private void editAccount(AccountProfileService.ProfileView account)
	{
		Object[] options = {"Rename", "Remove from device", "Cancel"};
		int choice = JOptionPane.showOptionDialog(this, "Manage this saved account", "Account", JOptionPane.DEFAULT_OPTION,
			JOptionPane.PLAIN_MESSAGE, null, options, options[2]);
		if (choice == 0)
		{
			String label = JOptionPane.showInputDialog(this, "Local account label", account.label);
			if (label != null) run("Saving label…", () -> profiles.rename(account.id, label));
		}
		else if (choice == 1)
		{
			if (confirm("Remove saved account", "Remove this account and its saved characters from this device?\n\nYour Jagex account and game progress are not deleted. An active game session will not be logged out.", "Remove account"))
				run("Removing saved account…", () -> { profiles.remove(account.id); login.clearSelection(); });
		}
	}

	private void login() { run("Checking session…", () -> login.login().get()); }

	private boolean confirm(String title, String message, String action)
	{
		JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		JPanel body = new JPanel(new BorderLayout(0, 16));
		body.setBackground(BACKGROUND);
		body.setBorder(BorderFactory.createEmptyBorder(18, 18, 16, 18));
		JTextArea explanation = text(message);
		explanation.setForeground(TEXT);
		explanation.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(explanation);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getViewport().setBackground(BACKGROUND);
		scroll.setPreferredSize(new Dimension(344, 180));
		body.add(scroll, BorderLayout.CENTER);
		boolean[] accepted = {false};
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.setOpaque(false);
		actions.add(button("Cancel", false, dialog::dispose));
		JButton proceed = button(action, true, () -> { accepted[0] = true; dialog.dispose(); });
		actions.add(proceed); body.add(actions, BorderLayout.SOUTH);
		dialog.setContentPane(body);
		dialog.getRootPane().setDefaultButton(proceed);
		dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(), javax.swing.KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(380, 260));
		dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
		dialog.setVisible(true);
		return accepted[0];
	}

	void close()
	{
		closed = true; ++epoch; timer.stop(); auth.cancel(); result = null; attempt = null; accounts = List.of(); worker.shutdownNow();
	}

	private static String message(Throwable error)
	{
		while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause();
		return error instanceof ProfileException ? error.getMessage() : "Account operation could not finish. Please retry.";
	}

	private static void clearMatchingClipboard(char[] value)
	{
		try
		{
			java.awt.datatransfer.Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			if (clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)
				&& new String(value).equals(clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor)))
				clipboard.setContents(new StringSelection(""), null);
		}
		catch (Exception ignored) { /* Some desktops temporarily lock their clipboard. */ }
	}

	private static JPanel vertical()
	{
		JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false); panel.setAlignmentX(Component.LEFT_ALIGNMENT); return panel;
	}

	private static JTextArea text(String value)
	{
		JTextArea area = new JTextArea(value); area.setLineWrap(true); area.setWrapStyleWord(true); area.setEditable(false);
		area.setFocusable(false); area.setOpaque(false); area.setForeground(MUTED); area.setFont(UI_FONT);
		area.setMinimumSize(new Dimension(0, 0));
		area.setAlignmentX(Component.LEFT_ALIGNMENT); return area;
	}

	private static JLabel label(String value, int size, Color color)
	{
		JLabel label = new JLabel(value); label.putClientProperty("html.disable", true); label.setForeground(color);
		label.setFont(UI_FONT.deriveFont(Font.BOLD, size)); label.setAlignmentX(Component.LEFT_ALIGNMENT); return label;
	}

	private static JButton button(String title, boolean primary, Runnable action)
	{
		JButton button = new JButton(title); button.putClientProperty("html.disable", true); button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setFont(UI_FONT);
		button.setForeground(TEXT); button.setBackground(primary ? BLUE : CARD); button.setFocusPainted(false);
		button.setBorder(BorderFactory.createEmptyBorder(primary ? 10 : 7, 8, primary ? 10 : 7, 8));
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, primary ? 38 : 32));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); button.addActionListener(event -> action.run()); return button;
	}
	private void gap(int height) { add(Box.createVerticalStrut(height)); }
	@FunctionalInterface private interface Work { void run() throws Exception; }
}
