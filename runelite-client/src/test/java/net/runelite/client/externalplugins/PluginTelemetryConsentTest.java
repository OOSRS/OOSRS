package net.runelite.client.externalplugins;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.PluginManager;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class PluginTelemetryConsentTest
{
	private ExternalPluginManager manager(ConfigManager config, ExternalPluginClient client, ScheduledExecutorService scheduler) throws Exception
	{
		Constructor<ExternalPluginManager> constructor = ExternalPluginManager.class.getDeclaredConstructor(ConfigManager.class,
			ExternalPluginClient.class, ScheduledExecutorService.class, PluginManager.class, EventBus.class, OkHttpClient.class, Gson.class);
		constructor.setAccessible(true);
		return constructor.newInstance(config, client, scheduler, mock(PluginManager.class), mock(EventBus.class), mock(OkHttpClient.class), new Gson());
	}
	@Test public void noScheduleOrSubmissionWithoutExplicitConsent() throws Exception
	{
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		ExternalPluginClient client = mock(ExternalPluginClient.class);
		manager(mock(ConfigManager.class), client, scheduler);
		verifyNoInteractions(scheduler); verify(client, never()).submitPlugins(anyList());
	}

	@Test public void consentRevocationAndShutdownCancelQueuedWork() throws Exception
	{
		ConfigManager config = mock(ConfigManager.class);
		ExternalPluginClient client = mock(ExternalPluginClient.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		java.util.concurrent.ScheduledFuture<?> scheduled = mock(java.util.concurrent.ScheduledFuture.class);
		doReturn(scheduled).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
		ExternalPluginManager manager = manager(config, client, scheduler);
		when(config.getConfiguration("runelite", "sharePluginUsage", Boolean.class)).thenReturn(true);
		when(config.getConfiguration("runelite", "externalPlugins")).thenReturn("fixture-hub-plugin");
		net.runelite.client.events.ConfigChanged event = new net.runelite.client.events.ConfigChanged();
		event.setGroup("runelite"); event.setKey("sharePluginUsage");
		manager.onConfigChanged(event);
		org.mockito.ArgumentCaptor<Runnable> task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
		verify(scheduler).scheduleWithFixedDelay(task.capture(), eq(180L), eq(180L), eq(java.util.concurrent.TimeUnit.MINUTES));
		task.getValue().run(); verify(client).submitPlugins(java.util.List.of("fixture-hub-plugin"));
		when(config.getConfiguration("runelite", "sharePluginUsage", Boolean.class)).thenReturn(false);
		manager.onConfigChanged(event); verify(scheduled).cancel(false); clearInvocations(client);
		task.getValue().run(); verify(client, never()).submitPlugins(anyList());
		when(config.getConfiguration("runelite", "sharePluginUsage", Boolean.class)).thenReturn(true);
		manager.onConfigChanged(event); manager.onClientShutdown(new net.runelite.client.events.ClientShutdown());
		clearInvocations(client, scheduler); task.getValue().run(); manager.onConfigChanged(event);
		verify(client, never()).submitPlugins(anyList()); verifyNoInteractions(scheduler);
	}

	private ExternalPluginClient httpClient(OkHttpClient http) throws Exception
	{
		Constructor<ExternalPluginClient> constructor = ExternalPluginClient.class.getDeclaredConstructor(OkHttpClient.class, Gson.class, okhttp3.HttpUrl.class);
		constructor.setAccessible(true);
		return constructor.newInstance(http, new Gson(), okhttp3.HttpUrl.get("https://api.example/"));
	}

	@Test public void httpBoundaryRequiresConsentAndCancelsInflightCall() throws Exception
	{
		OkHttpClient http = mock(OkHttpClient.class); okhttp3.Call call = mock(okhttp3.Call.class);
		when(http.newCall(any())).thenReturn(call);
		ExternalPluginClient client = httpClient(http);
		client.submitPlugins(java.util.List.of("fixture")); verifyNoInteractions(http);
		client.setPluginSubmissionEnabled(true); client.submitPlugins(java.util.List.of("fixture"));
		org.mockito.ArgumentCaptor<okhttp3.Request> request = org.mockito.ArgumentCaptor.forClass(okhttp3.Request.class);
		verify(http).newCall(request.capture());
		okio.Buffer body = new okio.Buffer(); request.getValue().body().writeTo(body);
		org.junit.Assert.assertEquals("[\"fixture\"]", body.readUtf8());
		org.junit.Assert.assertEquals("POST", request.getValue().method());
		client.setPluginSubmissionEnabled(false); verify(call).cancel();
		client.submitPlugins(java.util.List.of("fixture")); verify(http, times(1)).newCall(any());
	}

	@Test public void completedSubmissionClosesBodyAndFunctionalReadsRemainAvailable() throws Exception
	{
		OkHttpClient http = mock(OkHttpClient.class); okhttp3.Call call = mock(okhttp3.Call.class);
		when(http.newCall(any())).thenReturn(call); ExternalPluginClient client = httpClient(http);
		client.setPluginSubmissionEnabled(true); client.submitPlugins(java.util.List.of("fixture"));
		org.mockito.ArgumentCaptor<okhttp3.Callback> callback = org.mockito.ArgumentCaptor.forClass(okhttp3.Callback.class);
		verify(call).enqueue(callback.capture()); okhttp3.Response response = mock(okhttp3.Response.class);
		callback.getValue().onResponse(call, response); verify(response).close();
		client.setPluginSubmissionEnabled(false); verify(call, never()).cancel();
		okhttp3.Request read = new okhttp3.Request.Builder().url("https://api.example/pluginhub").build();
		when(call.execute()).thenReturn(new okhttp3.Response.Builder().request(read).protocol(okhttp3.Protocol.HTTP_1_1)
			.code(200).message("OK").body(okhttp3.ResponseBody.create(okhttp3.MediaType.get("application/json"), "{\"fixture\":1}")).build());
		org.junit.Assert.assertEquals(Integer.valueOf(1), client.getPluginCounts().get("fixture"));
	}
}
