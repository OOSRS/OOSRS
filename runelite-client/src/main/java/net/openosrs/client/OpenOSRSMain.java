package net.openosrs.client;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.runelite.client.RuneLite;

/** Launches the client with the Java module access required by desktop plugins. */
public final class OpenOSRSMain
{
	private OpenOSRSMain() { }

	public static void main(String[] args) throws Exception
	{
		if (Arrays.asList(args).contains("--version"))
		{
			System.out.println("OpenOSRS " + com.openosrs.client.OpenOSRS.SYSTEM_VERSION + " — Java " + Runtime.version().feature());
			return;
		}
		if (Runtime.version().feature() < 11)
		{
			throw new IllegalStateException("OpenOSRS requires Java 11 or newer.");
		}
		if (!Boolean.getBoolean("openosrs.bootstrap.ready"))
		{
			String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
			List<String> command = new ArrayList<>();
			command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
			command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
			command.add("-Dopenosrs.bootstrap.ready=true");
			command.add("-Duser.home=" + System.getProperty("user.home"));
			for (String module : new String[]{"java.desktop/java.awt", "java.desktop/java.awt.peer",
				"java.desktop/java.awt.event", "java.base/java.net", "java.base/java.io", "java.base/java.nio",
				"java.base/java.lang", "java.base/java.util", "java.base/java.util.concurrent",
				"java.base/java.util.concurrent.atomic"})
			{
				command.add("--add-opens=" + module + "=ALL-UNNAMED");
			}
			command.add("-cp");
			command.add(System.getProperty("java.class.path"));
			command.add(OpenOSRSMain.class.getName());
			command.addAll(Arrays.asList(args));
			System.exit(waitForChild(new ProcessBuilder(command).inheritIO().start()));
		}
		RuneLite.main(args);
	}

	static int waitForChild(Process child) throws InterruptedException
	{
		Thread cleanup = new Thread(() -> stopChild(child), "openosrs-child-cleanup");
		Runtime runtime = Runtime.getRuntime();
		runtime.addShutdownHook(cleanup);
		try { return child.waitFor(); }
		catch (InterruptedException interrupted)
		{
			stopChild(child);
			Thread.currentThread().interrupt();
			throw interrupted;
		}
		finally
		{
			try { runtime.removeShutdownHook(cleanup); }
			catch (IllegalStateException shuttingDown) { /* The registered hook owns cleanup now. */ }
		}
	}

	static void stopChild(Process child)
	{
		if (!child.isAlive()) return;
		child.destroy();
		try { if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly(); }
		catch (InterruptedException interrupted)
		{
			child.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}
}
