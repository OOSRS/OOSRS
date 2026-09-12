/*
 * Copyright (c) 2019 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.externalplugins;

import java.lang.invoke.MethodHandles;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.Setter;
import net.runelite.client.util.ReflectUtil;

class ExternalPluginClassLoader extends URLClassLoader implements ReflectUtil.PrivateLookupableClassLoader
{
	@Getter
	private final ExternalPluginManifest manifest;

	@Getter
	private final String[] plugins;

	@Getter
	@Setter
	private MethodHandles.Lookup lookup;

	ExternalPluginClassLoader(ExternalPluginManifest manifest, URL[] urls, Gson gson) throws IOException
	{
		super(urls, ExternalPluginClassLoader.class.getClassLoader());
		this.manifest = manifest;
		// Read entry points only from this verified JAR, never a parent resource.
		try
		{
			URL resource = findResource("runelite_plugin.json");
			if (resource == null) { throw new IOException("Plugin entry points are missing"); }
			try (InputStream input = resource.openStream())
			{
				ExternalPluginManifest stub = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), ExternalPluginManifest.class);
				if (stub == null || stub.getPlugins() == null || stub.getPlugins().length == 0)
				{
					throw new IOException("Plugin entry points are invalid");
				}
				plugins = stub.getPlugins();
			}
			ReflectUtil.installLookupHelper(this);
		}
		catch (IOException | RuntimeException | LinkageError e)
		{
			try { close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
			throw new IOException("Unable to initialize plugin classloader", e);
		}
	}

	@Override
	public Class<?> defineClass0(String name, byte[] b, int off, int len) throws ClassFormatError
	{
		return super.defineClass(name, b, off, len);
	}
}
