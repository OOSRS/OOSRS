package net.runelite.client;

import java.util.Properties;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LaunchProvenanceTest
{
	@Test
	public void matchingBaselineHasNoMismatch()
	{
		final Properties baseline = properties();
		final Properties observed = properties();

		assertNull(LaunchProvenance.firstMismatch(baseline, observed));
	}

	@Test
	public void mismatchNamesTheFirstDifferentField()
	{
		final Properties baseline = properties();
		final Properties observed = properties();
		observed.setProperty("stackSha256", "different");

		assertEquals("stackSha256 expected stack but observed different",
			LaunchProvenance.firstMismatch(baseline, observed));
	}

	@Test
	public void missingBaselineFieldIsRejected()
	{
		final Properties baseline = properties();
		baseline.remove("artifactSha256");

		assertEquals("baseline is missing artifactSha256",
			LaunchProvenance.firstMismatch(baseline, properties()));
	}

	private static Properties properties()
	{
		final Properties properties = new Properties();
		properties.setProperty("upstreamVersion", "1.12.37");
		properties.setProperty("protocolRevision", "240");
		properties.setProperty("artifactSha256", "artifact");
		properties.setProperty("launcherVersion", "launcher");
		properties.setProperty("javaMajor", "21");
		properties.setProperty("stackSha256", "stack");
		return properties;
	}
}
