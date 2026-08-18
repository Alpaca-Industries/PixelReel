package com.pixelreel.plex;

import com.pixelreel.PixelReel;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.jellyfin.JellyfinItemSummary;
import com.pixelreel.jellyfin.JellyfinLibrary;
import com.pixelreel.jellyfin.JellyfinStatus;
import com.pixelreel.provider.AbstractCatalogService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** server-side Plex cache */
public final class PlexService extends AbstractCatalogService {
	public static final PlexService INSTANCE = new PlexService();

	private PlexService() {
		super("pixelreel-plex", PlexClient::createHttpClient);
	}

	@Override
	protected boolean isConfigured(PixelReelConfig config) {
		return config.isPlexConfigured();
	}

	@Override
	protected boolean isUrlValid(PixelReelConfig config) {
		return isUrlValid(config.plexUrl);
	}

	@Override
	protected long cacheSeconds(PixelReelConfig config) {
		return config.plexLibraryCacheSeconds;
	}

	@Override
	protected String providerLabel() {
		return "Plex";
	}

	@Override
	public CompletableFuture<Optional<JellyfinItemSummary>> fetchItem(String itemId) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!config.isPlexConfigured()) {
					return Optional.empty();
				}
				JellyfinItemSummary item = this.client(config).getItem(itemId);
				if (item != null) {
					this.indexSingleItem(item);
				}
				return Optional.ofNullable(item);
			} catch (Exception e) {
				PixelReel.LOGGER.warn("Failed to fetch Plex item {}: {}", itemId, PlexClient.sanitizeDetail(PlexClient.describeError(e)));
				return Optional.empty();
			}
		}, this.executor);
	}

	@Override
	public CompletableFuture<List<JellyfinItemSummary>> seasons(String seriesId, boolean force) {
		List<JellyfinItemSummary> cached = this.seasonsBySeries.getOrDefault(seriesId, List.of());
		if (!force && !cached.isEmpty()) {
			return CompletableFuture.completedFuture(cached);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				List<JellyfinItemSummary> seasons = this.client(config).listChildren(seriesId);
				sortByIndexThenTitle(seasons);
				this.storeSeasons(seriesId, seasons);
				return seasons;
			} catch (Exception e) {
				PixelReel.LOGGER.warn("Failed to load Plex seasons for {}: {}", seriesId, PlexClient.sanitizeDetail(PlexClient.describeError(e)));
				return cached;
			}
		}, this.executor);
	}

	@Override
	public CompletableFuture<List<JellyfinItemSummary>> episodes(String seasonId, boolean force) {
		List<JellyfinItemSummary> cached = this.episodesBySeason.getOrDefault(seasonId, List.of());
		if (!force && !cached.isEmpty()) {
			return CompletableFuture.completedFuture(cached);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				List<JellyfinItemSummary> episodes = this.client(config).listChildren(seasonId);
				sortByIndexThenTitle(episodes);
				this.storeEpisodes(seasonId, episodes);
				return episodes;
			} catch (Exception e) {
				PixelReel.LOGGER.warn("Failed to load Plex episodes for {}: {}", seasonId, PlexClient.sanitizeDetail(PlexClient.describeError(e)));
				return cached;
			}
		}, this.executor);
	}

	public CompletableFuture<Optional<PlexClient.PlaybackStart>> resolvePlayback(String itemId, long startPositionMs) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!config.isPlexConfigured()) {
					return Optional.empty();
				}
				return Optional.of(this.client(config).startPlayback(itemId, startPositionMs));
			} catch (Exception e) {
				PixelReel.LOGGER.warn("Failed to resolve Plex playback for {}: {}", itemId, PlexClient.sanitizeDetail(PlexClient.describeError(e)));
				return Optional.empty();
			}
		}, this.executor);
	}

	public String buildStreamUrl(
		String ratingKey,
		int mediaIndex,
		int partIndex,
		String partKey,
		String playSessionId,
		int subtitleStreamId,
		long startPositionMs
	) {
		PixelReelConfig config = ConfigManager.get();
		if (!config.isPlexConfigured()) {
			return "";
		}
		return this.client(config).buildStreamUrl(
			ratingKey, mediaIndex, partIndex, partKey, playSessionId, subtitleStreamId, startPositionMs
		);
	}

	public String buildSubtitleUrl(int subtitleStreamId) {
		PixelReelConfig config = ConfigManager.get();
		if (!config.isPlexConfigured()) {
			return "";
		}
		return this.client(config).buildSubtitleUrl(subtitleStreamId);
	}

	public void reportTimeline(String itemId, String state, long positionMs, long durationMs) {
		this.executor.execute(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!config.isPlexConfigured()) {
					return;
				}
				this.client(config).reportTimeline(itemId, state, positionMs, durationMs);
			} catch (Exception e) {
				PixelReel.LOGGER.debug("Plex timeline report skipped: {}", e.toString());
			}
		});
	}

	@Override
	public CompletableFuture<List<JellyfinLibrary>> discoverLibraries() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PixelReelConfig config = ConfigManager.get();
				if (!config.isPlexConfigured()) {
					return List.<JellyfinLibrary>of();
				}
				List<JellyfinLibrary> discovered = this.client(config).listLibraries();
				this.setLibraries(discovered);
				return discovered;
			} catch (Exception e) {
				PixelReel.LOGGER.warn("Failed to list Plex libraries: {}", PlexClient.sanitizeDetail(PlexClient.describeError(e)));
				this.setStatus(
					e instanceof PlexClient.PlexAuthException
						? JellyfinStatus.authFailed(PlexClient.sanitizeDetail(PlexClient.describeError(e)))
						: JellyfinStatus.offline(PlexClient.sanitizeDetail(PlexClient.describeError(e)))
				);
				return List.<JellyfinLibrary>of();
			}
		}, this.executor);
	}

	@Override
	protected JellyfinStatus scanBlocking(PixelReelConfig config) {
		try {
			PlexClient client = this.client(config);
			client.ping();
			List<JellyfinLibrary> discovered = client.listLibraries();
			this.setLibraries(discovered);

			List<JellyfinLibrary> enabled = enabledLibraries(discovered, config);
			if (enabled.isEmpty()) {
				this.clearCatalogue();
				JellyfinStatus status = JellyfinStatus.online(0, 0, "No permitted libraries are available");
				this.setStatus(status);
				this.markCacheFilled();
				return status;
			}

			List<JellyfinItemSummary> movieItems = new ArrayList<>();
			List<JellyfinItemSummary> seriesItems = new ArrayList<>();
			for (JellyfinLibrary library : enabled) {
				if (library.isMovies() && config.plexMoviesEnabled) {
					movieItems.addAll(client.listSectionItems(library.id(), "movie"));
				}
				if (library.isTvShows() && config.plexTvShowsEnabled) {
					seriesItems.addAll(client.listSectionItems(library.id(), "show"));
				}
			}
			sortByTitle(movieItems);
			sortByTitle(seriesItems);

			this.setCatalogue(movieItems, seriesItems);
			this.markCacheFilled();
			JellyfinStatus status = JellyfinStatus.online(movieItems.size(), seriesItems.size(), "Libraries ready");
			this.setStatus(status);
			PixelReel.LOGGER.info(
				"Plex library ready: {} movie(s), {} series from {} library(ies)",
				movieItems.size(),
				seriesItems.size(),
				enabled.size()
			);
			return status;
		} catch (PlexClient.PlexAuthException e) {
			JellyfinStatus status = JellyfinStatus.authFailed(PlexClient.sanitizeDetail(e.getMessage()));
			this.setStatus(status);
			PixelReel.LOGGER.warn("Plex authentication failed: {}", PlexClient.sanitizeDetail(e.getMessage()));
			return status;
		} catch (Exception e) {
			String detail = PlexClient.sanitizeDetail(PlexClient.describeError(e));
			JellyfinStatus status = JellyfinStatus.offline(detail);
			this.setStatus(status);
			PixelReel.LOGGER.warn("Plex scan failed: {}", detail);
			return status;
		}
	}

	private static List<JellyfinLibrary> enabledLibraries(List<JellyfinLibrary> discovered, PixelReelConfig config) {
		List<String> selected = config.plexLibraryKeys == null ? List.of() : config.plexLibraryKeys;
		return discovered.stream()
			.filter(library -> library.isMovies() || library.isTvShows())
			.filter(library -> {
				if (library.isMovies() && !config.plexMoviesEnabled) {
					return false;
				}
				if (library.isTvShows() && !config.plexTvShowsEnabled) {
					return false;
				}
				return selected.isEmpty() || selected.contains(library.id());
			})
			.collect(Collectors.toList());
	}

	private PlexClient client(PixelReelConfig config) {
		return new PlexClient(this.httpClient(config), config);
	}

	private static boolean isUrlValid(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		String lower = url.trim().toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}
}
