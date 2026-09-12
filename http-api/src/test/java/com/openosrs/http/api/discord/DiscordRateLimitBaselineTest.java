package com.openosrs.http.api.discord;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscordRateLimitBaselineTest
{
	@Test public void retryAfterMustNotCauseAnImmediateSecondRequest() throws Exception
	{
		CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
		AtomicInteger attempts = new AtomicInteger(); OkHttpClient previous = RuneLiteAPI.CLIENT;
		RuneLiteAPI.CLIENT = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			boolean initial = attempts.incrementAndGet() == 1;
			if (initial) { first.countDown(); } else { second.countDown(); }
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(initial ? 429 : 204).message("Fixture").header("Retry-After", "2.5")
				.body(ResponseBody.create(MediaType.get("application/json"), initial ? "{\"message\":\"You are being rate limited\",\"retry_after\":2.5}" : "")).build();
		}).build();
		DiscordClient client = new DiscordClient();
		try
		{
			client.message(HttpUrl.get("https://fixture.invalid/api/webhooks/1/fixture-token"), new DiscordMessage("fixture", "fixture", null, null));
			assertTrue(first.await(5, TimeUnit.SECONDS));
			assertFalse("Retry happened before the 2.5-second server deadline", second.await(200, TimeUnit.MILLISECONDS));
		}
		finally
		{
			if (client instanceof AutoCloseable) { ((AutoCloseable) client).close(); }
			RuneLiteAPI.CLIENT.dispatcher().executorService().shutdownNow(); RuneLiteAPI.CLIENT = previous;
		}
	}
}
