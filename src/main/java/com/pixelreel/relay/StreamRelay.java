package com.pixelreel.relay;

import com.pixelreel.PixelReel;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * Hides real media-server URLs (which carry Plex/Jellyfin/Emby API credentials as a query
 * parameter) from clients. Instead of handing a player the real URL, callers register it here
 * and hand out an opaque token URL pointing back at this relay; the relay fetches the real URL
 * server-side (where the credential already lives) and streams the response through.
 */
public final class StreamRelay {
	public static final StreamRelay INSTANCE = new StreamRelay();

	private static final long TOKEN_TTL_MILLIS = 6L * 60 * 60 * 1000;
	private static final int SWEEP_THRESHOLD = 2000;
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private record Entry(String targetUrl, long expiresAtMillis) {
	}

	private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private @Nullable HttpServer server;
	private volatile String publicBaseUrl = "";
	private volatile boolean warnedNoHost;

	private StreamRelay() {
	}

	public synchronized void start() {
		PixelReelConfig config = ConfigManager.get();
		if (!config.relayEnabled || this.server != null) {
			return;
		}
		String host = resolveHost(config);
		if (host.isEmpty()) {
			if (!this.warnedNoHost) {
				this.warnedNoHost = true;
				PixelReel.LOGGER.warn(
					"Stream relay disabled: could not determine a reachable host. "
						+ "Set relayPublicHost in pixelreel.json to your server's address to enable it "
						+ "(this keeps Plex/Jellyfin/Emby API keys out of URLs sent to players)."
				);
			}
			return;
		}
		try {
			this.server = HttpServer.create(new InetSocketAddress(config.relayPort), 0);
			this.server.createContext("/r/", this::handle);
			this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
				Thread thread = new Thread(runnable, "pixelreel-relay");
				thread.setDaemon(true);
				return thread;
			}));
			this.server.start();
			this.publicBaseUrl = "http://" + host + ":" + config.relayPort;
			PixelReel.LOGGER.info("Stream relay listening on port {} (public base {})", config.relayPort, this.publicBaseUrl);
		} catch (IOException e) {
			PixelReel.LOGGER.error("Could not start stream relay on port {}: {}", config.relayPort, e.toString());
			this.server = null;
		}
	}

	public synchronized void stop() {
		if (this.server != null) {
			this.server.stop(0);
			this.server = null;
			this.publicBaseUrl = "";
		}
		this.tokens.clear();
	}

	/**
	 * Wraps a real, credential-bearing URL behind an opaque relay token. Returns the input
	 * unchanged (never blank-to-null) if the relay isn't running, so playback still works even
	 * with the relay disabled or misconfigured -- just without the extra privacy.
	 */
	public String relay(@Nullable String realUrl) {
		if (realUrl == null || realUrl.isBlank()) {
			return realUrl == null ? "" : realUrl;
		}
		if (this.server == null || this.publicBaseUrl.isEmpty()) {
			return realUrl;
		}
		if (this.tokens.size() > SWEEP_THRESHOLD) {
			this.sweep();
		}
		String token = newToken();
		this.tokens.put(token, new Entry(realUrl, System.currentTimeMillis() + TOKEN_TTL_MILLIS));
		return this.publicBaseUrl + "/r/" + token;
	}

	private void sweep() {
		long now = System.currentTimeMillis();
		this.tokens.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
	}

	private String newToken() {
		byte[] bytes = new byte[18];
		this.random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private void handle(HttpExchange exchange) {
		try {
			String path = exchange.getRequestURI().getPath();
			String token = path.substring(path.lastIndexOf('/') + 1);
			Entry entry = this.tokens.get(token);
			if (entry == null || entry.expiresAtMillis() < System.currentTimeMillis()) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			this.tokens.put(token, new Entry(entry.targetUrl(), System.currentTimeMillis() + TOKEN_TTL_MILLIS));
			this.proxy(exchange, entry.targetUrl());
		} catch (Exception e) {
			PixelReel.LOGGER.debug("Stream relay request failed: {}", e.toString());
		} finally {
			exchange.close();
		}
	}

	private void proxy(HttpExchange exchange, String targetUrl) throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl)).timeout(Duration.ofSeconds(30)).GET();
		String range = exchange.getRequestHeaders().getFirst("Range");
		if (range != null) {
			builder.header("Range", range);
		}
		HttpResponse<InputStream> response;
		try {
			response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			exchange.sendResponseHeaders(502, -1);
			return;
		}
		response.headers().firstValue("Content-Type").ifPresent(v -> exchange.getResponseHeaders().set("Content-Type", v));
		response.headers().firstValue("Content-Range").ifPresent(v -> exchange.getResponseHeaders().set("Content-Range", v));
		response.headers().firstValue("Accept-Ranges").ifPresent(v -> exchange.getResponseHeaders().set("Accept-Ranges", v));
		long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
		try (InputStream body = response.body()) {
			exchange.sendResponseHeaders(response.statusCode(), length >= 0 ? length : 0);
			if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
				try (OutputStream out = exchange.getResponseBody()) {
					body.transferTo(out);
				}
			}
		}
	}

	private static String resolveHost(PixelReelConfig config) {
		if (!config.relayPublicHost.isBlank()) {
			return config.relayPublicHost.trim();
		}
		try {
			InetAddress local = InetAddress.getLocalHost();
			if (!local.isLoopbackAddress()) {
				return local.getHostAddress();
			}
		} catch (Exception ignored) {
		}
		return "";
	}
}
