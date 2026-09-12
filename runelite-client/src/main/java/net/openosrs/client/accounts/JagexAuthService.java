package net.openosrs.client.accounts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Browser-only Jagex sign-in. Never accepts passwords or exposes authorization responses to plugins. */
@Singleton
public final class JagexAuthService
{
	private static final String ORIGIN = "https://account.jagex.com";
	private static final String CLIENT_ID = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";
	private static final String SESSION_API = "https://auth.jagex.com/game-session/v1/";
	private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
		.followRedirects(false).followSslRedirects(false).build();
	private Attempt active;

	public static final class CharacterInfo
	{
		private final String id;
		private final String name;
		CharacterInfo(String id, String name) { this.id = id; this.name = name; }
		public String getId() { return id; }
		public String getName() { return name; }
	}

	public static final class Result
	{
		final String subject;
		final String session;
		private final List<CharacterInfo> characters;
		Result(String subject, String session, List<CharacterInfo> characters)
		{
			this.subject = subject; this.session = session; this.characters = List.copyOf(characters);
		}
		public List<CharacterInfo> getCharacters() { return characters; }
	}

	public static final class Attempt
	{
		private final String state = random();
		private final String nonce = random();
		private final long started = Instant.now().getEpochSecond();
		private final CompletableFuture<Result> result = new CompletableFuture<>();
		private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, task ->
		{
			Thread thread = new Thread(task, "openosrs-account-login"); thread.setDaemon(true); return thread;
		});
		private final ExecutorService callbacks = Executors.newFixedThreadPool(2, task ->
		{
			Thread thread = new Thread(task, "openosrs-account-callback"); thread.setDaemon(true); return thread;
		});
		private HttpServer server;
		private boolean received;
		private volatile URI login;
		public CompletableFuture<Result> result() { return result; }
		public boolean needsManualReturn() { return server == null; }
		public URI loginUri() { return login; }
		public void cancel() { result.completeExceptionally(new ProfileException("Sign-in cancelled.")); }
	}

	public synchronized Attempt begin() throws ProfileException
	{
		if (active != null && !active.result.isDone()) throw new ProfileException("A sign-in is already open.");
		Attempt attempt = new Attempt();
		active = attempt;
		try
		{
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 80), 0);
			attempt.server = server;
			server.createContext("/", exchange -> callback(attempt, exchange));
			server.setExecutor(attempt.callbacks);
			server.start();
		}
		catch (Exception e)
		{
			if (attempt.server != null) attempt.server.stop(0);
			attempt.server = null; // The registered localhost URI still works through manual completion.
		}
		attempt.result.whenComplete((value, error) ->
		{
			if (attempt.server != null) attempt.server.stop(0);
			attempt.executor.shutdownNow();
			attempt.callbacks.shutdownNow();
			synchronized (JagexAuthService.this) { if (active == attempt) active = null; }
		});
		attempt.executor.schedule(() -> attempt.result.completeExceptionally(new ProfileException("Sign-in timed out. Try again.")), 5, TimeUnit.MINUTES);
		// Match the reference client's registered hybrid flow. The code is validated via c_hash and discarded.
		attempt.login = URI.create(ORIGIN + "/oauth2/auth?client_id=" + CLIENT_ID
			+ "&redirect_uri=" + encode("http://localhost") + "&response_type=" + encode("id_token code")
			+ "&scope=" + encode("openid offline") + "&prompt=login&state=" + attempt.state + "&nonce=" + attempt.nonce);
		return attempt;
	}

	public void openBrowser(Attempt attempt) throws ProfileException
	{
		try
		{
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
				Desktop.getDesktop().browse(attempt.login);
			else if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux"))
			{
				Process process = new ProcessBuilder("xdg-open", attempt.login.toString())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() != 0) throw new IllegalStateException();
			}
			else throw new IllegalStateException();
		}
		catch (Exception e) { throw new ProfileException("Could not open your browser. Copy the sign-in link and open it manually."); }
	}

	/** Call only with a return URI pasted into the local masked field, never command-line arguments. */
	public void completeManually(Attempt attempt, char[] value) throws ProfileException
	{
		try
		{
			URI uri = URI.create(new String(value).trim());
			if (!"http".equals(uri.getScheme()) || !"localhost".equals(uri.getHost())
				|| (uri.getPort() != -1 && uri.getPort() != 80) || uri.getUserInfo() != null
				|| (!uri.getPath().isEmpty() && !"/".equals(uri.getPath())) || uri.getRawQuery() != null)
				throw new IllegalArgumentException();
			accept(attempt, parse(uri.getRawFragment()));
		}
		catch (ProfileException e) { throw e; }
		catch (Exception e) { throw new ProfileException("Paste the complete http://localhost/# return link from this sign-in attempt."); }
		finally { Arrays.fill(value, '\0'); }
	}

	public synchronized void cancel() { if (active != null) active.cancel(); }

	private void callback(Attempt attempt, HttpExchange exchange) throws java.io.IOException
	{
		try
		{
			String host = exchange.getRequestHeaders().getFirst("Host");
			if (!("localhost".equals(host) || "localhost:80".equals(host)) || !exchange.getRemoteAddress().getAddress().isLoopbackAddress())
			{
				reply(exchange, 403, "Invalid callback."); return;
			}
			String path = exchange.getRequestURI().getPath();
			if ("GET".equals(exchange.getRequestMethod()) && "/".equals(path) && exchange.getRequestURI().getRawQuery() == null)
			{
				String html = "<!doctype html><meta charset='utf-8'><title>OpenOSRS sign-in</title>"
					+ "<p id='status'>Completing sign-in…</p><script nonce='" + attempt.state + "'>"
					+ "const value=location.hash.slice(1);history.replaceState(null,'','/');"
					+ "fetch('/complete',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:value})"
					+ ".then(r=>{document.getElementById('status').textContent=r.ok?'Return to OpenOSRS to choose a character.':'Return to OpenOSRS and try again.';})"
					+ ".catch(()=>{document.getElementById('status').textContent='Return to OpenOSRS to check sign-in.';});</script>";
				exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + attempt.state + "'; connect-src 'self'; frame-ancestors 'none'");
				reply(exchange, 200, html); return;
			}
			if ("POST".equals(exchange.getRequestMethod()) && "/complete".equals(path)
				&& "http://localhost".equals(exchange.getRequestHeaders().getFirst("Origin")))
			{
				byte[] bytes = exchange.getRequestBody().readNBytes(32769);
				try
				{
					if (bytes.length > 32768) throw new IllegalArgumentException();
					accept(attempt, parse(new String(bytes, StandardCharsets.UTF_8)));
					reply(exchange, 200, "Return to OpenOSRS.");
				}
				finally { Arrays.fill(bytes, (byte) 0); }
				return;
			}
			reply(exchange, 404, "Not found.");
		}
		catch (Exception e) { reply(exchange, 400, "Invalid or expired callback. Return to OpenOSRS."); }
		finally { exchange.close(); }
	}

	private void accept(Attempt attempt, Map<String, String> params) throws ProfileException
	{
		synchronized (attempt)
		{
			if (attempt.result.isDone() || attempt.received || !constant(attempt.state, params.get("state")))
				throw new ProfileException("This return link does not match the current sign-in.");
			if (params.containsKey("error")) { attempt.cancel(); return; }
			if (!params.containsKey("id_token") || !params.containsKey("code"))
				throw new ProfileException("The sign-in response was incomplete. Start again.");
			attempt.received = true;
		}
		attempt.executor.execute(() ->
		{
			try
			{
				String token = params.get("id_token");
				String subject = verify(token, params.get("code"), attempt);
				if (attempt.result.isDone()) return;
				JsonObject body = new JsonObject(); body.addProperty("idToken", token);
				JsonObject session = request(new Request.Builder().url(SESSION_API + "sessions")
					.post(RequestBody.create(MediaType.get("application/json"), body.toString())).build()).getAsJsonObject();
				String id = required(session, "sessionId");
				if (attempt.result.isDone()) return;
				List<CharacterInfo> characters = characters(id);
				if (characters.isEmpty()) throw new ProfileException("No game characters were found on this Jagex account.");
				attempt.result.complete(new Result(subject, id, characters));
			}
			catch (ProfileException e) { attempt.result.completeExceptionally(e); }
			catch (Exception e) { attempt.result.completeExceptionally(new ProfileException("Jagex sign-in could not be verified. Please try again.")); }
			finally { params.clear(); }
		});
	}

	List<CharacterInfo> characters(String session) throws ProfileException
	{
		try
		{
			JsonArray list = request(new Request.Builder().url(SESSION_API + "accounts").header("Authorization", "Bearer " + session).build()).getAsJsonArray();
			List<CharacterInfo> result = new ArrayList<>();
			java.util.Set<String> ids = new java.util.HashSet<>();
			for (JsonElement element : list)
			{
				JsonObject value = element.getAsJsonObject();
				String id = required(value, "accountId");
				String name = value.has("displayName") && !value.get("displayName").isJsonNull() ? value.get("displayName").getAsString() : "Unnamed character";
				if (name.isBlank()) name = "Unnamed character";
				if (!ids.add(id) || result.size() >= 100 || name.length() > 200) throw new IllegalArgumentException();
				result.add(new CharacterInfo(id, name));
			}
			return result;
		}
		catch (ProfileException e) { throw e; }
		catch (Exception e) { throw new ProfileException("Could not read the character list. Please retry."); }
	}

	private String verify(String token, String code, Attempt attempt) throws Exception
	{
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3 || token.length() > 24000) throw new IllegalArgumentException();
		JsonObject header = new JsonParser().parse(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
		if (!"RS256".equals(required(header, "alg"))) throw new IllegalArgumentException();
		JsonArray keys = request(new Request.Builder().url(ORIGIN + "/.well-known/jwks.json").build()).getAsJsonObject().getAsJsonArray("keys");
		JsonObject key = null;
		for (JsonElement value : keys)
		{
			JsonObject candidate = value.getAsJsonObject();
			if (required(candidate, "kid").equals(required(header, "kid")))
			{
				if (key != null) throw new IllegalArgumentException();
				key = candidate;
			}
		}
		if (key == null || !"RSA".equals(required(key, "kty")) || !"sig".equals(required(key, "use"))) throw new IllegalArgumentException();
		RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, Base64.getUrlDecoder().decode(required(key, "n"))),
			new BigInteger(1, Base64.getUrlDecoder().decode(required(key, "e"))));
		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(spec));
		signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalArgumentException();
		JsonObject claims = new JsonParser().parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
		long now = Instant.now().getEpochSecond();
		JsonElement audience = claims.get("aud");
		boolean correctAudience = audience != null && (audience.isJsonPrimitive() ? CLIENT_ID.equals(audience.getAsString())
			: audience.getAsJsonArray().size() == 1 && CLIENT_ID.equals(audience.getAsJsonArray().get(0).getAsString()));
		if (!correctAudience || !constant(attempt.nonce, required(claims, "nonce")) || !(ORIGIN + "/").equals(required(claims, "iss"))
			|| claims.get("exp").getAsLong() <= now || claims.get("iat").getAsLong() > now + 60
			|| claims.get("iat").getAsLong() < attempt.started - 60
			|| claims.has("nbf") && claims.get("nbf").getAsLong() > now + 60
			|| claims.has("azp") && !CLIENT_ID.equals(claims.get("azp").getAsString())) throw new IllegalArgumentException();
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));
		if (!constant(Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 16)), required(claims, "c_hash"))) throw new IllegalArgumentException();
		return required(claims, "sub");
	}

	private JsonElement request(Request request) throws ProfileException
	{
		try (Response response = http.newCall(request).execute())
		{
			if (response.code() == 401) throw new ProfileException("Reconnect required: Jagex did not accept this session.");
			if (response.code() == 403) throw new ProfileException("Jagex denied this request. Retry in your browser; the saved account has been kept.");
			if (!response.isSuccessful() || response.body() == null) throw new ProfileException("Jagex is unavailable. Try again shortly.");
			byte[] body = response.body().byteStream().readNBytes(1_000_001);
			try
			{
				if (body.length > 1_000_000) throw new IllegalArgumentException();
				return new JsonParser().parse(new String(body, StandardCharsets.UTF_8));
			}
			finally { Arrays.fill(body, (byte) 0); }
		}
		catch (ProfileException e) { throw e; }
		catch (Exception e) { throw new ProfileException("Cannot reach Jagex. Check your connection and retry."); }
	}

	private static String required(JsonObject object, String field)
	{
		String value = object.get(field).getAsString();
		if (value.isBlank() || value.length() > 8192) throw new IllegalArgumentException();
		return value;
	}

	private static Map<String, String> parse(String query)
	{
		if (query == null || query.length() > 32768) throw new IllegalArgumentException();
		Map<String, String> result = new HashMap<>();
		for (String pair : query.split("&"))
		{
			String[] fields = pair.split("=", 2);
			if (fields.length != 2 || result.put(URLDecoder.decode(fields[0], StandardCharsets.UTF_8),
				URLDecoder.decode(fields[1], StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
		}
		return result;
	}

	private static void reply(HttpExchange exchange, int status, String text) throws java.io.IOException
	{
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	private static boolean constant(String a, String b)
	{
		return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
	private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
	private static String random()
	{
		byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
