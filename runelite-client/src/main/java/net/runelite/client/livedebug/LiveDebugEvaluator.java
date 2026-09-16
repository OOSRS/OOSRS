/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import jdk.jshell.DeclarationSnippet;
import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.jshell.RLShellExecutionControl;

@Slf4j
@Singleton
public class LiveDebugEvaluator
{
	private final ClientThread clientThread;
	private JShell shell;
	private RLShellExecutionControl exec;
	private boolean initialized = false;

	@Inject
	public LiveDebugEvaluator(ClientThread clientThread)
	{
		this.clientThread = clientThread;
	}

	public synchronized void init()
	{
		if (initialized)
		{
			return;
		}

		try
		{
			exec = new RLShellExecutionControl()
			{
				@Override
				protected String invoke(Method doitMethod) throws Exception
				{
					AtomicReference<Object> result = new AtomicReference<>();
					Semaphore sema = new Semaphore(0);

					clientThread.invoke(() ->
					{
						try
						{
							result.set(super.invoke(doitMethod));
						}
						catch (Throwable t)
						{
							result.set(t);
						}
						finally
						{
							sema.release();
						}
					});

					sema.acquire();
					Object res = result.get();
					if (res instanceof String)
					{
						return (String) res;
					}
					if (res instanceof Exception)
					{
						throw (Exception) res;
					}
					if (res instanceof Throwable)
					{
						throw new RuntimeException((Throwable) res);
					}
					return null;
				}
			};

			shell = JShell.builder()
				.executionEngine(exec, null)
				.build();

			loadPrelude();
			initialized = true;
			log.info("LiveDebugEvaluator initialized successfully");
		}
		catch (Throwable t)
		{
			log.error("Failed to initialize LiveDebug JShell evaluator", t);
		}
	}

	private void loadPrelude()
	{
		evalInternal("import java.util.*;");
		evalInternal("import java.util.stream.*;");
		evalInternal("import net.runelite.api.*;");
		evalInternal("import net.runelite.api.coords.*;");
		evalInternal("import net.runelite.api.widgets.*;");
		evalInternal("import net.runelite.client.callback.*;");
		evalInternal("import net.openosrs.api.*;");
		evalInternal("import net.openosrs.api.dispatch.*;");
		evalInternal("import net.openosrs.api.service.movement.*;");
		evalInternal("import net.openosrs.api.service.bank.*;");
		evalInternal("import net.openosrs.api.service.inventory.*;");
		evalInternal("import net.openosrs.api.service.dialogue.*;");
		evalInternal("import net.runelite.client.livedebug.*;");

		evalInternal("var client = LiveDebugContext.getClient();");
		evalInternal("var clientThread = LiveDebugContext.getClientThread();");
		evalInternal("var packets = LiveDebugContext.getPacketDispatcher();");
		evalInternal("var menu = LiveDebugContext.getMenuDispatcher();");
		evalInternal("var overlay = LiveDebugContext.getOverlay();");
	}

	private void evalInternal(String src)
	{
		if (shell != null)
		{
			shell.eval(src);
		}
	}

	public synchronized JsonObject evaluate(String src)
	{
		JsonObject response = new JsonObject();
		if (!initialized || shell == null)
		{
			init();
			if (!initialized)
			{
				response.addProperty("status", "ERROR");
				response.addProperty("error", "Evaluator could not be initialized");
				return response;
			}
		}

		if (src == null || src.trim().isEmpty())
		{
			response.addProperty("status", "EMPTY");
			return response;
		}

		JsonArray snippetResults = new JsonArray();
		JsonArray diagnosticsArray = new JsonArray();
		String lastValue = null;
		boolean hasError = false;

		for (int offset = 0; offset < src.length(); )
		{
			while (offset < src.length() && src.charAt(offset) == '\n')
			{
				offset++;
			}
			if (offset >= src.length())
			{
				break;
			}

			SourceCodeAnalysis.CompletionInfo ci = shell.sourceCodeAnalysis().analyzeCompletion(src.substring(offset));
			offset = src.length() - ci.remaining().length();
			if (ci.completeness() == SourceCodeAnalysis.Completeness.EMPTY)
			{
				break;
			}

			List<SnippetEvent> events = shell.eval(ci.source());
			for (SnippetEvent ev : events)
			{
				JsonObject evObj = new JsonObject();
				Snippet snip = ev.snippet();
				evObj.addProperty("id", snip.id());
				evObj.addProperty("status", ev.status().name());

				if (ev.status() != Snippet.Status.VALID && ev.status() != Snippet.Status.RECOVERABLE_DEFINED)
				{
					hasError = true;
					List<Diag> diags = shell.diagnostics(snip).collect(Collectors.toList());
					for (Diag d : diags)
					{
						diagnosticsArray.add(d.getMessage(Locale.ENGLISH));
					}
					if (snip instanceof DeclarationSnippet)
					{
						List<String> unres = shell.unresolvedDependencies((DeclarationSnippet) snip).collect(Collectors.toList());
						for (String u : unres)
						{
							diagnosticsArray.add("Unresolved dependency: " + u);
						}
					}
				}

				if (ev.exception() != null)
				{
					hasError = true;
					evObj.addProperty("exception", ev.exception().toString());
				}

				if (ev.value() != null)
				{
					evObj.addProperty("value", ev.value());
					lastValue = ev.value();
				}

				snippetResults.add(evObj);
			}
		}

		response.addProperty("status", hasError ? "ERROR" : "OK");
		if (lastValue != null)
		{
			response.addProperty("result", lastValue);
		}
		response.add("snippets", snippetResults);
		if (diagnosticsArray.size() > 0)
		{
			response.add("diagnostics", diagnosticsArray);
		}

		return response;
	}

	public synchronized void reset()
	{
		close();
		init();
	}

	public synchronized void close()
	{
		if (shell != null)
		{
			try
			{
				shell.close();
			}
			catch (Exception ignored)
			{
			}
			shell = null;
		}
		initialized = false;
	}
}
