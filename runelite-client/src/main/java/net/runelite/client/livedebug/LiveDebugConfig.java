/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import java.io.File;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LiveDebugConfig
{
	public static final int DEFAULT_PORT = 9876;
	public static final String DEFAULT_HOST = "127.0.0.1";

	@Builder.Default
	boolean enabled = false;

	@Builder.Default
	int port = DEFAULT_PORT;

	@Builder.Default
	String host = DEFAULT_HOST;

	File sessionDir;
}
