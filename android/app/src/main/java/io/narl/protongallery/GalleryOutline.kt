package io.narl.protongallery

import androidx.compose.runtime.mutableStateMapOf
import io.narl.protongallery.bridge.AlbumGrouping
import io.narl.protongallery.bridge.GalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Every item of a view in display order: its id, time and shape. The grid plans
 * all rows from this once, so the layout never moves while records load; only
 * a new outline, after media was added or removed, changes it.
 *
 * Records load in pages as rows ask for them and stay in a bounded set of
 * pages. Use it from the main thread.
 */
class GalleryOutline(
    val ids: List<String>,
    val times: LongArray,
    /** Width divided by height; 0 when not known yet. */
    val ratios: FloatArray,
    private val scope: CoroutineScope,
    private val load: suspend (start: Int, count: Int) -> List<GalleryItem?>,
) {
    val count: Int get() = ids.size
    private val pages = mutableStateMapOf<Int, List<GalleryItem?>>()
    private val loading = HashSet<Int>()
    // Pages by last use, oldest first.
    private val used = LinkedHashSet<Int>()
    private val positions by lazy { HashMap<String, Int>(ids.size * 2).apply { ids.forEachIndexed { index, id -> put(id, index) } } }

    // Section starts of the whole outline by grouping; worked out once, as
    // every zoom step plans again. Planning runs off the main thread.
    private val allSections = java.util.concurrent.ConcurrentHashMap<AlbumGrouping, IntArray>()

    /** The items of `from` until `until` where a new group starts, `from` first. */
    fun sections(grouping: AlbumGrouping, from: Int = 0, until: Int = count): IntArray {
        if (from == 0 && until == count) allSections[grouping]?.let { return it }
        val zone = java.time.ZoneId.systemDefault()
        val starts = IntArrayList()
        var last = 0L
        for (index in from until until) {
            val group = groupOf(times[index], grouping, zone)
            if (index == from || group != last) starts.add(index)
            last = group
        }
        val result = starts.toArray()
        if (from == 0 && until == count) allSections[grouping] = result
        return result
    }

    private class IntArrayList {
        private var values = IntArray(16)
        private var size = 0
        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }
        fun toArray() = values.copyOf(size)
    }

    /** The shape the grid plans with: unknown shapes are square. */
    fun ratio(index: Int): Double = ratios[index].takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0

    fun indexOf(id: String): Int = positions[id] ?: -1

    /** The record at `index`, or null while it loads. Reading it in a
     * composable recomposes when it arrives. */
    fun item(index: Int): GalleryItem? {
        if (index !in ids.indices) return null
        val page = index / PAGE
        val loaded = pages[page]
        if (loaded == null) request(page) else touch(page)
        return loaded?.getOrNull(index % PAGE)
    }

    /** The records already loaded, without loading more. */
    fun loaded(index: Int): GalleryItem? = pages[index / PAGE]?.getOrNull(index % PAGE)

    /** Start loading the pages of `range` ahead of the rows that show them. */
    fun prefetch(range: IntRange) {
        val first = range.first.coerceAtLeast(0) / PAGE
        val last = range.last.coerceAtMost(count - 1) / PAGE
        for (page in first..last) if (pages[page] == null) request(page)
    }

    /** Load the page around `index` now, so a new outline shows the place the
     * old one was at without empty tiles. */
    suspend fun preload(index: Int) {
        if (index !in ids.indices) return
        val page = index / PAGE
        if (pages[page] != null) return
        runCatching { pages[page] = fetch(page) }.onFailure { if (it is CancellationException) throw it }
        touch(page)
    }

    /** Read the loaded records again, for changes that keep the layout, such
     * as an upload finishing or a new favorite. Old records show until then. */
    fun reload() {
        for (page in pages.keys.toList()) scope.launch {
            try { pages[page] = fetch(page) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }

    /** The record at `index`, loading it if needed. */
    suspend fun fetchItem(index: Int): GalleryItem? {
        if (index !in ids.indices) return null
        loaded(index)?.let { return it }
        val page = index / PAGE
        val records = fetch(page)
        pages[page] = records
        touch(page)
        return records.getOrNull(index % PAGE)
    }

    /** The newest index a row asked for; a new outline keeps this place. */
    var lastIndex = 0
        private set

    private fun request(page: Int) {
        lastIndex = page * PAGE
        if (!loading.add(page)) return
        scope.launch {
            try {
                pages[page] = fetch(page)
                touch(page)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
            finally { loading.remove(page) }
        }
    }

    private suspend fun fetch(page: Int): List<GalleryItem?> {
        val start = page * PAGE
        val records = load(start, minOf(PAGE, count - start))
        // A record that no longer matches its place (the catalog changed) shows
        // as loading until the next outline replaces this one.
        return records.mapIndexed { offset, item -> item?.takeIf { it.id == ids.getOrNull(start + offset) } }
    }

    private fun touch(page: Int) {
        used.remove(page)
        used.add(page)
        while (used.size > MAX_PAGES) {
            val oldest = used.first()
            used.remove(oldest)
            pages.remove(oldest)
        }
    }

    companion object {
        const val PAGE = 60
        private const val MAX_PAGES = 48

        /** An outline of records already in memory; for previews and tests. */
        fun of(items: List<GalleryItem>): GalleryOutline = GalleryOutline(
            items.map { it.id }, LongArray(items.size) { items[it].sortTime },
            FloatArray(items.size) { (items[it].aspectRatio ?: 0.0).toFloat() },
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ) { start, count -> items.subList(start, start + count) }.also { outline ->
            items.chunked(PAGE).forEachIndexed { page, records -> outline.pages[page] = records; outline.used.add(page) }
        }
    }
}
