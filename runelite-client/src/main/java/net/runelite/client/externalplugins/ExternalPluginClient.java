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

import com.google.common.reflect.TypeToken;
import com.google.common.hash.HashCode;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.util.VerificationException;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

@Slf4j
public class ExternalPluginClient
{
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final HttpUrl apiBase;
	private boolean pluginSubmissionEnabled;
	private final java.util.Set<Call> pluginSubmissions = new java.util.HashSet<>();

	@Inject
	private ExternalPluginClient(OkHttpClient okHttpClient,
		Gson gson,
		@Named("runelite.api.base") HttpUrl apiBase
	)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.apiBase = apiBase;
	}

	public List<ExternalPluginManifest> downloadManifest() throws IOException, VerificationException
	{
		HttpUrl manifest = RuneLiteProperties.getPluginHubBase()
			.newBuilder()
			.addPathSegment("manifest")
			.addPathSegment(RuneLiteProperties.getPluginHubVersion() + "_full.js")
			.build();
		try (Response res = okHttpClient.newCall(new Request.Builder().url(manifest).build()).execute())
		{
			if (res.code() != 200)
			{
				throw new IOException("Non-OK response code: " + res.code());
			}

			BufferedSource src = res.body().source();

			int signatureLength = src.readInt();
			if (signatureLength < 1 || signatureLength > 8192)
			{
				throw new VerificationException("Invalid external plugin manifest signature length");
			}
			byte[] signature = new byte[signatureLength];
			src.readFully(signature);

			byte[] data = src.readByteArray();
			Signature s = Signature.getInstance("SHA256withRSA");
			s.initVerify(loadCertificate());
			s.update(data);

			if (!s.verify(signature))
			{
				throw new VerificationException("Unable to verify external plugin manifest");
			}

			HubManifest catalog = gson.fromJson(new String(data, StandardCharsets.UTF_8), HubManifest.class);
			if (catalog == null || catalog.display == null || catalog.jars == null)
			{
				throw new IOException("Incomplete external plugin manifest");
			}
			Map<String, ExternalPluginManifest> display = new HashMap<>();
			for (ExternalPluginManifest plugin : catalog.display)
			{
				display.put(plugin.getInternalName(), plugin);
			}
			List<ExternalPluginManifest> available = new ArrayList<>();
			for (HubJar jar : catalog.jars)
			{
				ExternalPluginManifest plugin = display.get(jar.internalName);
				if (plugin == null || jar.internalName == null || !jar.internalName.matches("[a-zA-Z0-9_-]+")
					|| jar.jarHash == null || !jar.jarHash.matches("[a-zA-Z0-9_-]{43}") || jar.jarSize <= 0)
				{
					throw new IOException("Invalid external plugin entry");
				}
				plugin.setJarHash(jar.jarHash);
				plugin.setHash(HashCode.fromBytes(Base64.getUrlDecoder().decode(jar.jarHash)).toString());
				plugin.setSize(jar.jarSize);
				available.add(plugin);
			}
			return available;
		}
		catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e)
		{
			throw new VerificationException(e);
		}
		catch (JsonSyntaxException | IllegalArgumentException e)
		{
			throw new IOException("Invalid external plugin manifest", e);
		}
	}

	// RuneLite publishes display metadata and version-compatible JARs separately.
	private static class HubManifest
	{
		private List<ExternalPluginManifest> display;
		private List<HubJar> jars;
	}

	private static class HubJar
	{
		private String internalName;
		private String jarHash;
		private int jarSize;
	}

	HttpUrl getJarURL(ExternalPluginManifest plugin)
	{
		return RuneLiteProperties.getPluginHubBase().newBuilder()
			.addPathSegment("jar")
			.addPathSegment(plugin.getInternalName() + "_" + plugin.getJarHash() + ".jar")
			.build();
	}

	public BufferedImage downloadIcon(ExternalPluginManifest plugin) throws IOException
	{
		if (!plugin.hasIcon())
		{
			return null;
		}

		HttpUrl url = RuneLiteProperties.getPluginHubBase()
			.newBuilder()
			.addPathSegment("icon")
			.addPathSegment(plugin.getInternalName() + "_" + plugin.getIconHash() + ".png")
			.build();

		try (Response res = okHttpClient.newCall(new Request.Builder().url(url).build()).execute())
		{
			if (!res.isSuccessful())
			{
				throw new IOException("Unable to download plugin icon: HTTP " + res.code());
			}
			byte[] bytes = res.body().bytes();
			// We don't stream so the lock doesn't block the edt trying to load something at the same time
			synchronized (ImageIO.class)
			{
				return ImageIO.read(new ByteArrayInputStream(bytes));
			}
		}
	}

	private static Certificate loadCertificate()
	{
		try (InputStream in = ExternalPluginClient.class.getResourceAsStream("externalplugins.crt"))
		{
			CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
			return certFactory.generateCertificate(in);
		}
		catch (CertificateException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	/** Revocation prevents new posts and cancels posts already in flight where possible. */
	synchronized void setPluginSubmissionEnabled(boolean enabled)
	{
		pluginSubmissionEnabled = enabled;
		if (!enabled)
		{
			for (Call call : pluginSubmissions) { call.cancel(); }
			pluginSubmissions.clear();
		}
	}

	synchronized void submitPlugins(List<String> plugins)
	{
		if (!pluginSubmissionEnabled || plugins.isEmpty()) { return; }
		HttpUrl url = apiBase.newBuilder().addPathSegment("pluginhub").build();
		Request request = new Request.Builder().url(url)
			.post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(new ArrayList<>(plugins)))).build();
		Call submission = okHttpClient.newCall(request);
		pluginSubmissions.add(submission);
		try
		{
			submission.enqueue(new Callback()
			{
				@Override public void onFailure(Call call, IOException e)
				{
					finishSubmission(call);
					log.debug("Optional plugin usage submission did not complete");
				}
				@Override public void onResponse(Call call, Response response)
				{
					try (Response ignored = response) { }
					finally { finishSubmission(call); }
				}
			});
		}
		catch (RuntimeException e) { pluginSubmissions.remove(submission); throw e; }
	}

	private synchronized void finishSubmission(Call call) { pluginSubmissions.remove(call); }

	public Map<String, Integer> getPluginCounts() throws IOException
	{
		HttpUrl url = apiBase
			.newBuilder()
			.addPathSegments("pluginhub")
			.build();
		try (Response res = okHttpClient.newCall(new Request.Builder().url(url).build()).execute())
		{
			if (res.code() != 200)
			{
				throw new IOException("Non-OK response code: " + res.code());
			}

			// CHECKSTYLE:OFF
			return gson.fromJson(new InputStreamReader(res.body().byteStream()), new TypeToken<Map<String, Integer>>(){}.getType());
			// CHECKSTYLE:ON
		}
		catch (JsonSyntaxException ex)
		{
			throw new IOException(ex);
		}
	}
}
