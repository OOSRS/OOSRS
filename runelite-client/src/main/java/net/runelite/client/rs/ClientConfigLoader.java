/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
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
package net.runelite.client.rs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

class ClientConfigLoader
{
    private static final int MAX_BYTES = 256 * 1024;
    private static final int TIMEOUT_MILLIS = 15000;
    private final int timeoutMillis;
    private final OkHttpClient okHttpClient;

    ClientConfigLoader(OkHttpClient client)
    {
        this(client, TIMEOUT_MILLIS);
    }

    ClientConfigLoader(OkHttpClient client, int timeoutMillis)
    {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Positive timeout required");
        this.timeoutMillis = timeoutMillis;
        okHttpClient = client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build();
    }

    RSConfig fetch(HttpUrl url, byte[] raw) throws IOException { return parse(raw); }

    RSConfig fetch(HttpUrl url) throws IOException
    {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Config fetch interrupted");
        Call call = okHttpClient.newCall(new Request.Builder().url(url).build());
        CompletableFuture<RSConfig> result = new CompletableFuture<>();
        call.enqueue(new Callback()
        {
            @Override public void onFailure(Call failed, IOException error) { result.completeExceptionally(error); }
            @Override public void onResponse(Call completed, Response response)
            {
                try (Response closed = response)
                {
                    if (!response.isSuccessful() || response.body() == null)
                        throw new IOException("Config request failed: HTTP " + response.code());
                    if (response.body().contentLength() > MAX_BYTES) throw new IOException("Config response too large");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    InputStream input = response.body().byteStream();
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) != -1)
                    {
                        if (count > MAX_BYTES - bytes.size()) throw new IOException("Config response too large");
                        bytes.write(chunk, 0, count);
                    }
                    RSConfig config = parse(bytes.toByteArray());
                    validate(config);
                    result.complete(config);
                }
                catch (IOException | RuntimeException error) { result.completeExceptionally(error); }
            }
        });
        try { return result.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error)
        {
            call.cancel(); result.cancel(false); Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Config fetch interrupted");
            failure.initCause(error); throw failure;
        }
        catch (TimeoutException error)
        {
            call.cancel(); result.cancel(false);
            throw new java.net.SocketTimeoutException("Config fetch exceeded total time budget");
        }
        catch (ExecutionException error)
        {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Invalid config response", cause);
        }
    }

    static RSConfig parse(byte[] raw) throws IOException
    {
        if (raw == null || raw.length > MAX_BYTES) throw new IOException("Missing or oversized config");
        // jav_config is served as ISO-8859-1, including the copyright byte in msg lines.
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        RSConfig config = new RSConfig();
        for (String line : text.split("\\r?\\n"))
        {
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            if (separator == 0) throw new IOException("Empty config key");
            String key = line.substring(0, separator), value = line.substring(separator + 1);
            if (key.equals("msg")) continue;
            if (key.equals("param"))
            {
                separator = value.indexOf('=');
                if (separator <= 0) throw new IOException("Malformed config parameter");
                config.getAppletProperties().put(value.substring(0, separator), value.substring(separator + 1));
            }
            else config.getClassLoaderProperties().put(key, value);
        }
        return config;
    }

    private static void validate(RSConfig config) throws IOException
    {
        if (config.getCodeBase() == null || HttpUrl.parse(config.getCodeBase()) == null
            || config.getInitialJar() == null || config.getInitialJar().isEmpty()
            || config.getInitialClass() == null || config.getInitialClass().isEmpty())
            throw new IOException("Invalid or missing jav_config fields");
    }
}
