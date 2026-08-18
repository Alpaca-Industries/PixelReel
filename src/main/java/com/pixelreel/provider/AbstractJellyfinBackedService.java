package com.pixelreel.provider;

import com.pixelreel.PixelReel;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.jellyfin.JellyfinClient;
import com.pixelreel.jellyfin.JellyfinItemSummary;
import com.pixelreel.jellyfin.JellyfinLibrary;
import com.pixelreel.jellyfin.JellyfinStatus;
import com.pixelreel.ondemand.EmbyStyleConnection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Everything Jellyfin and Emby share: both are plain Jellyfin-protocol servers accessed
 * through {@link JellyfinClient}, differing only in which config fields back the connection.
 */
public abstract class AbstractJellyfinBackedService extends AbstractCatalogService {
	private volatile String resolvedUserId = "";

	protected AbstractJellyfinBackedService(String threadName) {
		super(threadName, JellyfinClient::createHttpClient);
	}

	public final String resolvedUserId() {
		return this.resolvedUserId;
	}

	@Override
	public void invalidateCache() {
		super.invalidateCache();
		this.resolvedUserId = "";
	}

	protected abstract EmbyStyleConnection connection(PixelReelConfig config);

	protected abstract boolean moviesEnabled(PixelReelConfig config);

	protected abstract boolean tvShowsEnabled(PixelReelConfig config);

	protected abstract List<String> libraryIds(PixelReelConfig config);

	protected abstract void saveResolvedUserId(String userId);

	@Override
	protected final boolean isConfigured(PixelReelConfig config) {
		return this.connection(config).isConfigured();
	}

	@Override
	protected final boolean isUrlValid(PixelReelConfig config) {
		return JellyfinClient.isUrlValid(this.connection(config).baseUrl());
	}

	protected final JellyfinClient client(PixelReelConfig config) {
		return new JellyfinClient(this.httpClient(config), this.connection(config), config.networkTimeoutSeconds);
	}

	private String ensureUserId(JellyfinClient client, PixelReelConfig config) throws Exception {
		String configured = this.connection(config).userId();
		if (configured != null && !configured.isBlank()) {
			this.resolvedUserId = configured.trim();
			return this.resolvedUserId;
		}
		if (!this.resolvedUserId.isBlank()) {
			return this.resolvedUserId;
		}
		String userId = client.resolveUserId();
		this.resolvedUserId = userId;
		if (!userId.isBlank()) {
			this.saveResolvedUserId(userId);
		}
		return userId;
	}

	@Override
	public final CompletableFuture<Optional<JellyfinItemSummary>> fetchItem(String itemId) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!this.isConfigured(config)) {
					return Optional.<JellyfinItemSummary>empty();
				}
				JellyfinClient client = this.client(config);
				String userId = this.ensureUserId(client, config);
				JellyfinItemSummary item = client.getItem(userId, itemId);
				if (item != null) {
					this.indexSingleItem(item);
				}
				return Optional.ofNullable(item);
			} catch (Exception e) {
				PixelReel.LOGGER.warn(
					"Failed to fetch {} item {}: {}", this.providerLabel(), itemId, JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e))
				);
				return Optional.<JellyfinItemSummary>empty();
			}
		}, this.executor);
	}

	@Override
	public final CompletableFuture<List<JellyfinItemSummary>> seasons(String seriesId, boolean force) {
		List<JellyfinItemSummary> cached = this.seasonsBySeries.getOrDefault(seriesId, List.of());
		if (!force && !cached.isEmpty()) {
			return CompletableFuture.completedFuture(cached);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				JellyfinClient client = this.client(config);
				String userId = this.ensureUserId(client, config);
				List<JellyfinItemSummary> seasons = client.listChildren(userId, seriesId, "Season");
				sortByIndexThenTitle(seasons);
				this.storeSeasons(seriesId, seasons);
				return seasons;
			} catch (Exception e) {
				PixelReel.LOGGER.warn(
					"Failed to load {} seasons for {}: {}", this.providerLabel(), seriesId, JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e))
				);
				return cached;
			}
		}, this.executor);
	}

	@Override
	public final CompletableFuture<List<JellyfinItemSummary>> episodes(String seasonId, boolean force) {
		List<JellyfinItemSummary> cached = this.episodesBySeason.getOrDefault(seasonId, List.of());
		if (!force && !cached.isEmpty()) {
			return CompletableFuture.completedFuture(cached);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				JellyfinClient client = this.client(config);
				String userId = this.ensureUserId(client, config);
				List<JellyfinItemSummary> episodes = client.listChildren(userId, seasonId, "Episode");
				sortByIndexThenTitle(episodes);
				this.storeEpisodes(seasonId, episodes);
				return episodes;
			} catch (Exception e) {
				PixelReel.LOGGER.warn(
					"Failed to load {} episodes for {}: {}", this.providerLabel(), seasonId, JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e))
				);
				return cached;
			}
		}, this.executor);
	}

	public final CompletableFuture<Optional<JellyfinClient.PlaybackStart>> resolvePlayback(String itemId, long startPositionMs) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!this.isConfigured(config)) {
					return Optional.<JellyfinClient.PlaybackStart>empty();
				}
				JellyfinClient client = this.client(config);
				String userId = this.ensureUserId(client, config);
				long ticks = Math.max(0L, startPositionMs) * 10_000L;
				return Optional.of(client.startPlayback(userId, itemId, ticks));
			} catch (Exception e) {
				PixelReel.LOGGER.warn(
					"Failed to resolve {} playback for {}: {}", this.providerLabel(), itemId, JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e))
				);
				return Optional.<JellyfinClient.PlaybackStart>empty();
			}
		}, this.executor);
	}

	public final String buildStreamUrl(String itemId, String mediaSourceId, String playSessionId, int subtitleIndex) {
		PixelReelConfig config = ConfigManager.get();
		if (!this.isConfigured(config)) {
			return "";
		}
		return this.client(config).buildStreamUrl(itemId, mediaSourceId, playSessionId, subtitleIndex);
	}

	public final String buildSubtitleUrl(String itemId, String mediaSourceId, int subtitleIndex) {
		PixelReelConfig config = ConfigManager.get();
		if (!this.isConfigured(config)) {
			return "";
		}
		return this.client(config).buildSubtitleUrl(itemId, mediaSourceId, subtitleIndex);
	}

	public final void reportPlaying(String itemId, String mediaSourceId, String playSessionId, long positionMs, boolean paused) {
		this.executor.execute(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!this.isConfigured(config)) {
					return;
				}
				this.client(config).reportPlaying(itemId, mediaSourceId, playSessionId, positionMs * 10_000L, paused);
			} catch (Exception e) {
				PixelReel.LOGGER.debug("{} playing report skipped: {}", this.providerLabel(), e.toString());
			}
		});
	}

	public final void reportProgress(String itemId, String mediaSourceId, String playSessionId, long positionMs, boolean paused) {
		this.executor.execute(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!this.isConfigured(config)) {
					return;
				}
				this.client(config).reportProgress(itemId, mediaSourceId, playSessionId, positionMs * 10_000L, paused);
			} catch (Exception e) {
				PixelReel.LOGGER.debug("{} progress report skipped: {}", this.providerLabel(), e.toString());
			}
		});
	}

	public final void reportStopped(String itemId, String mediaSourceId, String playSessionId, long positionMs, boolean completed) {
		this.executor.execute(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!this.isConfigured(config)) {
					return;
				}
				this.client(config).reportStopped(itemId, mediaSourceId, playSessionId, positionMs * 10_000L, completed);
			} catch (Exception e) {
				PixelReel.LOGGER.debug("{} stopped report skipped: {}", this.providerLabel(), e.toString());
			}
		});
	}

	@Override
	public final CompletableFuture<List<JellyfinLibrary>> discoverLibraries() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!this.isConfigured(config)) {
					return List.<JellyfinLibrary>of();
				}
				JellyfinClient client = this.client(config);
				String userId = this.ensureUserId(client, config);
				List<JellyfinLibrary> discovered = client.listLibraries(userId);
				this.setLibraries(discovered);
				return discovered;
			} catch (Exception e) {
				PixelReel.LOGGER.warn("Failed to list {} libraries: {}", this.providerLabel(), JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e)));
				this.setStatus(
					e instanceof JellyfinClient.JellyfinAuthException
						? JellyfinStatus.authFailed(JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e)))
						: JellyfinStatus.offline(JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e)))
				);
				return List.<JellyfinLibrary>of();
			}
		}, this.executor);
	}

	@Override
	protected final JellyfinStatus scanBlocking(PixelReelConfig config) {
		try {
			JellyfinClient client = this.client(config);
			client.getSystemInfo();
			String userId = this.ensureUserId(client, config);
			List<JellyfinLibrary> discovered = client.listLibraries(userId);
			this.setLibraries(discovered);

			List<JellyfinLibrary> enabled = this.enabledLibraries(discovered, config);
			if (enabled.isEmpty()) {
				this.clearCatalogue();
				JellyfinStatus status = JellyfinStatus.online(0, 0, "No permitted libraries are available");
				this.setStatus(status);
				this.markCacheFilled();
				return status;
			}

			List<String> movieParents = enabled.stream().filter(JellyfinLibrary::isMovies).map(JellyfinLibrary::id).toList();
			List<String> showParents = enabled.stream().filter(JellyfinLibrary::isTvShows).map(JellyfinLibrary::id).toList();

			List<JellyfinItemSummary> movieItems = List.of();
			List<JellyfinItemSummary> seriesItems = List.of();
			if (this.moviesEnabled(config) && !movieParents.isEmpty()) {
				movieItems = client.listItems(userId, movieParents, "Movie", null, true);
				sortByTitle(movieItems);
			}
			if (this.tvShowsEnabled(config) && !showParents.isEmpty()) {
				seriesItems = client.listItems(userId, showParents, "Series", null, true);
				sortByTitle(seriesItems);
			}

			this.setCatalogue(movieItems, seriesItems);
			this.markCacheFilled();
			JellyfinStatus status = JellyfinStatus.online(movieItems.size(), seriesItems.size(), "Libraries ready");
			this.setStatus(status);
			PixelReel.LOGGER.info(
				"{} library ready ({}): {} movie(s), {} series from {} library(ies)",
				this.providerLabel(),
				JellyfinClient.hostOnly(this.connection(config).baseUrl()),
				movieItems.size(),
				seriesItems.size(),
				enabled.size()
			);
			return status;
		} catch (JellyfinClient.JellyfinAuthException e) {
			JellyfinStatus status = JellyfinStatus.authFailed(JellyfinClient.sanitizeDetail(e.getMessage()));
			this.setStatus(status);
			PixelReel.LOGGER.warn("{} authentication failed: {}", this.providerLabel(), JellyfinClient.sanitizeDetail(e.getMessage()));
			return status;
		} catch (Exception e) {
			String detail = JellyfinClient.sanitizeDetail(JellyfinClient.describeError(e));
			JellyfinStatus status = JellyfinStatus.offline(detail);
			this.setStatus(status);
			PixelReel.LOGGER.warn("{} scan failed: {}", this.providerLabel(), detail);
			return status;
		}
	}

	private List<JellyfinLibrary> enabledLibraries(List<JellyfinLibrary> discovered, PixelReelConfig config) {
		List<String> selected = this.libraryIds(config);
		boolean movies = this.moviesEnabled(config);
		boolean shows = this.tvShowsEnabled(config);
		return discovered.stream()
			.filter(library -> library.isMovies() || library.isTvShows())
			.filter(library -> {
				if (library.isMovies() && !movies) {
					return false;
				}
				if (library.isTvShows() && !shows) {
					return false;
				}
				return selected.isEmpty() || selected.contains(library.id());
			})
			.collect(Collectors.toList());
	}
}
