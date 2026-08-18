package com.pixelreel.provider;

import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.jellyfin.JellyfinItemSummary;
import com.pixelreel.jellyfin.JellyfinLibrary;
import com.pixelreel.jellyfin.JellyfinStatus;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Shared scaffolding for the Plex/Jellyfin/Emby on-demand catalogue caches: refresh
 * scheduling, movie/series/season/episode caches, paging, and HttpClient reuse. Subclasses
 * only need to supply the provider-specific fetch/report calls.
 */
public abstract class AbstractCatalogService {
	public static final int PAGE_SIZE = 48;
	private static final long MIN_REFRESH_INTERVAL_MILLIS = 5000L;

	protected final ExecutorService executor;
	private final BiFunction<PixelReelConfig, ExecutorService, HttpClient> httpClientFactory;

	private volatile JellyfinStatus lastStatus = JellyfinStatus.notConfigured();
	private volatile List<JellyfinLibrary> libraries = List.of();
	protected volatile List<JellyfinItemSummary> movies = List.of();
	protected volatile List<JellyfinItemSummary> series = List.of();
	protected volatile Map<String, List<JellyfinItemSummary>> seasonsBySeries = Map.of();
	protected volatile Map<String, List<JellyfinItemSummary>> episodesBySeason = Map.of();
	protected volatile Map<String, JellyfinItemSummary> itemsById = Map.of();
	private volatile long cacheFilledAtMillis;
	private @Nullable CompletableFuture<JellyfinStatus> inFlight;
	private volatile @Nullable HttpClient httpClient;
	private volatile int httpClientTimeoutSeconds = -1;
	private volatile long lastRefreshAttemptMillis;

	protected AbstractCatalogService(String threadName, BiFunction<PixelReelConfig, ExecutorService, HttpClient> httpClientFactory) {
		this.httpClientFactory = httpClientFactory;
		this.executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, threadName);
			thread.setDaemon(true);
			return thread;
		});
	}

	public final JellyfinStatus lastStatus() {
		return this.lastStatus;
	}

	public final List<JellyfinLibrary> libraries() {
		return this.libraries;
	}

	public final boolean isCacheFresh() {
		PixelReelConfig config = ConfigManager.get();
		return this.lastStatus.authenticated()
			&& System.currentTimeMillis() - this.cacheFilledAtMillis < this.cacheSeconds(config) * 1000L;
	}

	public void invalidateCache() {
		this.cacheFilledAtMillis = 0L;
	}

	public final synchronized CompletableFuture<JellyfinStatus> refresh(boolean force) {
		PixelReelConfig config = ConfigManager.get();
		if (!this.isConfigured(config)) {
			this.lastStatus = JellyfinStatus.notConfigured();
			this.clearCatalogue();
			return CompletableFuture.completedFuture(this.lastStatus);
		}
		if (!this.isUrlValid(config)) {
			this.lastStatus = JellyfinStatus.offline("Invalid " + this.providerLabel() + " URL");
			return CompletableFuture.completedFuture(this.lastStatus);
		}
		if (!force && this.isCacheFresh()) {
			return CompletableFuture.completedFuture(this.lastStatus);
		}
		if (this.inFlight != null && !this.inFlight.isDone()) {
			return this.inFlight;
		}
		long now = System.currentTimeMillis();
		if (now - this.lastRefreshAttemptMillis < MIN_REFRESH_INTERVAL_MILLIS) {
			return CompletableFuture.completedFuture(this.lastStatus);
		}
		this.lastRefreshAttemptMillis = now;
		CompletableFuture<JellyfinStatus> future = CompletableFuture.supplyAsync(() -> this.scanBlocking(config), this.executor);
		this.inFlight = future;
		return future;
	}

	protected abstract boolean isConfigured(PixelReelConfig config);

	protected abstract boolean isUrlValid(PixelReelConfig config);

	protected abstract long cacheSeconds(PixelReelConfig config);

	protected abstract String providerLabel();

	protected abstract JellyfinStatus scanBlocking(PixelReelConfig config);

	protected final void setStatus(JellyfinStatus status) {
		this.lastStatus = status;
	}

	protected final void setLibraries(List<JellyfinLibrary> discovered) {
		this.libraries = List.copyOf(discovered);
	}

	protected final void markCacheFilled() {
		this.cacheFilledAtMillis = System.currentTimeMillis();
	}

	/** replaces the movie/series catalogue and rebuilds the item index from scratch (used after a full scan). */
	protected final void setCatalogue(List<JellyfinItemSummary> movieItems, List<JellyfinItemSummary> seriesItems) {
		this.movies = List.copyOf(movieItems);
		this.series = List.copyOf(seriesItems);
		this.seasonsBySeries = Map.of();
		this.episodesBySeason = Map.of();
		Map<String, JellyfinItemSummary> index = new LinkedHashMap<>();
		for (JellyfinItemSummary item : movieItems) {
			index.put(item.id(), item);
		}
		for (JellyfinItemSummary item : seriesItems) {
			index.put(item.id(), item);
		}
		this.itemsById = Map.copyOf(index);
	}

	protected final void clearCatalogue() {
		this.movies = List.of();
		this.series = List.of();
		this.seasonsBySeries = Map.of();
		this.episodesBySeason = Map.of();
		this.itemsById = Map.of();
	}

	protected final void index(List<JellyfinItemSummary> items) {
		Map<String, JellyfinItemSummary> copy = new LinkedHashMap<>(this.itemsById);
		for (JellyfinItemSummary item : items) {
			copy.put(item.id(), item);
		}
		this.itemsById = Map.copyOf(copy);
	}

	protected final void storeSeasons(String seriesId, List<JellyfinItemSummary> seasons) {
		Map<String, List<JellyfinItemSummary>> copy = new LinkedHashMap<>(this.seasonsBySeries);
		copy.put(seriesId, List.copyOf(seasons));
		this.seasonsBySeries = Map.copyOf(copy);
		this.index(seasons);
	}

	protected final void storeEpisodes(String seasonId, List<JellyfinItemSummary> episodes) {
		Map<String, List<JellyfinItemSummary>> copy = new LinkedHashMap<>(this.episodesBySeason);
		copy.put(seasonId, List.copyOf(episodes));
		this.episodesBySeason = Map.copyOf(copy);
		this.index(episodes);
	}

	protected final void indexSingleItem(JellyfinItemSummary item) {
		if (item == null) {
			return;
		}
		Map<String, JellyfinItemSummary> copy = new LinkedHashMap<>(this.itemsById);
		copy.put(item.id(), item);
		this.itemsById = Map.copyOf(copy);
	}

	public final List<JellyfinItemSummary> movies(@Nullable String search) {
		return filter(this.movies, search);
	}

	public final List<JellyfinItemSummary> series(@Nullable String search) {
		return filter(this.series, search);
	}

	public final Page pageMovies(String search, int page) {
		return pageOf(this.movies(search), page);
	}

	public final Page pageSeries(String search, int page) {
		return pageOf(this.series(search), page);
	}

	public final Optional<JellyfinItemSummary> find(String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.itemsById.get(itemId));
	}

	public abstract CompletableFuture<Optional<JellyfinItemSummary>> fetchItem(String itemId);

	public abstract CompletableFuture<List<JellyfinItemSummary>> seasons(String seriesId, boolean force);

	public abstract CompletableFuture<List<JellyfinItemSummary>> episodes(String seasonId, boolean force);

	public abstract CompletableFuture<List<JellyfinLibrary>> discoverLibraries();

	public final Optional<JellyfinItemSummary> findNextEpisode(String seriesId, String seasonId, int episodeNumber) {
		List<JellyfinItemSummary> episodes = this.episodesBySeason.getOrDefault(seasonId, List.of());
		for (JellyfinItemSummary episode : episodes) {
			if (episode.indexNumber() > episodeNumber) {
				return Optional.of(episode);
			}
		}
		List<JellyfinItemSummary> seasons = this.seasonsBySeries.getOrDefault(seriesId, List.of());
		int currentSeasonIndex = -1;
		for (int i = 0; i < seasons.size(); i++) {
			if (seasons.get(i).id().equals(seasonId)) {
				currentSeasonIndex = i;
				break;
			}
		}
		if (currentSeasonIndex < 0) {
			return Optional.empty();
		}
		for (int i = currentSeasonIndex + 1; i < seasons.size(); i++) {
			JellyfinItemSummary nextSeason = seasons.get(i);
			List<JellyfinItemSummary> nextEpisodes = this.episodesBySeason.getOrDefault(nextSeason.id(), List.of());
			if (!nextEpisodes.isEmpty()) {
				return Optional.of(nextEpisodes.getFirst());
			}
		}
		return Optional.empty();
	}

	public final CompletableFuture<Optional<JellyfinItemSummary>> resolveNextEpisode(String seriesId, String seasonId, int episodeNumber) {
		return this.seasons(seriesId, false).thenCompose(seasons -> {
			Optional<JellyfinItemSummary> local = this.findNextEpisode(seriesId, seasonId, episodeNumber);
			if (local.isPresent()) {
				return CompletableFuture.completedFuture(local);
			}
			List<CompletableFuture<List<JellyfinItemSummary>>> loads = new ArrayList<>();
			boolean passedCurrent = false;
			for (JellyfinItemSummary season : seasons) {
				if (season.id().equals(seasonId)) {
					passedCurrent = true;
					loads.add(this.episodes(season.id(), false));
					continue;
				}
				if (passedCurrent) {
					loads.add(this.episodes(season.id(), false));
				}
			}
			if (loads.isEmpty()) {
				return CompletableFuture.completedFuture(Optional.empty());
			}
			return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
				.thenApply(v -> this.findNextEpisode(seriesId, seasonId, episodeNumber));
		});
	}

	protected static void sortByIndexThenTitle(List<JellyfinItemSummary> items) {
		items.sort(Comparator.comparingInt(JellyfinItemSummary::indexNumber).thenComparing(JellyfinItemSummary::title));
	}

	protected static void sortByTitle(List<JellyfinItemSummary> items) {
		items.sort(Comparator.comparing(JellyfinItemSummary::title, String.CASE_INSENSITIVE_ORDER));
	}

	protected final HttpClient httpClient(PixelReelConfig config) {
		HttpClient existing = this.httpClient;
		if (existing != null && this.httpClientTimeoutSeconds == config.networkTimeoutSeconds) {
			return existing;
		}
		synchronized (this) {
			if (this.httpClient == null || this.httpClientTimeoutSeconds != config.networkTimeoutSeconds) {
				this.httpClient = this.httpClientFactory.apply(config, this.executor);
				this.httpClientTimeoutSeconds = config.networkTimeoutSeconds;
			}
			return this.httpClient;
		}
	}

	private static List<JellyfinItemSummary> filter(List<JellyfinItemSummary> source, @Nullable String search) {
		if (search == null || search.isBlank()) {
			return source;
		}
		String q = search.trim().toLowerCase(Locale.ROOT);
		List<JellyfinItemSummary> filtered = new ArrayList<>();
		for (JellyfinItemSummary item : source) {
			if (item.title().toLowerCase(Locale.ROOT).contains(q) || item.seriesName().toLowerCase(Locale.ROOT).contains(q)) {
				filtered.add(item);
			}
		}
		return filtered;
	}

	private static Page pageOf(List<JellyfinItemSummary> items, int page) {
		int safePage = Math.max(0, page);
		int from = safePage * PAGE_SIZE;
		if (from >= items.size()) {
			return new Page(List.of(), safePage, items.size());
		}
		int to = Math.min(items.size(), from + PAGE_SIZE);
		return new Page(items.subList(from, to), safePage, items.size());
	}

	public record Page(List<JellyfinItemSummary> items, int page, int totalCount) {
		public int totalPages() {
			return this.totalCount == 0 ? 0 : (this.totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
		}
	}
}
