package org.koitharu.kotatsu.parsers.site.fr

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Parser for Aniverse (aniverse.fr), the platform that replaced raijin-scans.fr.
 *
 * Aniverse is a Next.js (App Router) app built on an AniList-style metadata model and the
 * `cdn.onefy.me` image CDN. Lists come from its JSON API, details and pages from the
 * server-rendered RSC payload (`self.__next_f.push([1,"..."])`):
 *  - `GET /api/manga?page={n}`              paginated catalogue, `{ "items": [...] }`;
 *  - `GET /api/quicksearch?q={q}&media=manga`  search, a flat JSON array;
 *  - `/manga/{slug}`                        detail page, embeds a "manga" object + "chapters";
 *  - `/read/{slug}/{chapterId}`             reader, embeds the page images on `cdn.onefy.me`.
 *
 * The enum id is kept as RAIJINSCANS so existing users keep their saved source selection.
 */
@MangaSourceParser("RAIJINSCANS", "Aniverse", "fr")
internal class Aniverse(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RAIJINSCANS, pageSize = 28) {

	override val configKeyDomain = ConfigKey.Domain("aniverse.fr")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	private companion object {
		// Width requested from the onefy resize endpoint (/t/w_<width>/...) for reader pages.
		// The service clamps to the image's native width, so a high value maximises quality
		// without upscaling small pages.
		private const val PAGE_WIDTH = 1600
	}

	private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val nextFPushRegex =
		Regex("""self\.__next_f\.push\(\s*\[\s*1\s*,\s*"(.*)"\s*]\s*\)""", RegexOption.DOT_MATCHES_ALL)

	// Matches a reader page image, capturing (1) the chapter folder and (2) the page number:
	// https://cdn.onefy.me/t/manga/<uuid>/c101-<hash>/001.jpg
	// and its resize variants  https://cdn.onefy.me/t/w_720/manga/<uuid>/c101-<hash>/001.jpg
	// Posters (/poster-*) and chapter thumbnails (/chapter-covers/*) don't match the /c<n>-<hash>/ form.
	private val pageImageRegex = Regex(
		"""https://cdn\.onefy\.me/t/(?:w_\d+/)?manga/[0-9a-fA-F-]+/(c\d+-[0-9a-zA-Z]+)/(\d+)\.(?:jpg|jpeg|png|webp|gif|avif)""",
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val items: JSONArray = if (!filter.query.isNullOrEmpty()) {
			// quicksearch returns a flat, un-paginated array of matches.
			if (page > searchPaginator.firstPage) return emptyList()
			val url = "https://$domain/api/quicksearch?q=${filter.query.urlEncoded()}&media=manga"
			webClient.httpGet(url).parseJsonArray()
		} else {
			val url = "https://$domain/api/manga?page=$page"
			webClient.httpGet(url).parseJson().optJSONArray("items") ?: return emptyList()
		}
		return (0 until items.length())
			.mapNotNull { items.optJSONObject(it) }
			.mapNotNull { parseListManga(it) }
	}

	/** Builds a [Manga] from a catalogue (`/api/manga`) or search (`/api/quicksearch`) entry. */
	private fun parseListManga(json: JSONObject): Manga? {
		val slug = json.getStringOrNull("slug")?.takeIf { it.isNotBlank() } ?: return null
		val url = "/manga/$slug"
		val title = json.getStringOrNull("title")
			?: json.getStringOrNull("titleEnglish")
			?: json.getStringOrNull("titleRomaji")
			?: slug
		// Search entries carry a 0..10 rating; catalogue entries have none.
		val ratingValue = json.optDouble("rating", -1.0)
		val rating = if (ratingValue > 0) (ratingValue / 10.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = setOfNotNull(
				json.getStringOrNull("titleEnglish")?.takeIf { !it.equals(title, ignoreCase = true) },
				json.getStringOrNull("titleRomaji")?.takeIf { !it.equals(title, ignoreCase = true) },
				json.getStringOrNull("titleNative")?.takeIf { !it.equals(title, ignoreCase = true) },
			),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = rating,
			contentRating = ContentRating.SAFE,
			coverUrl = json.getStringOrNull("image"),
			tags = emptySet(),
			state = parseStatus(json.getStringOrNull("status")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/manga/").substringBefore("/").trim('/')
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val blob = decodePayload(doc)

		val detail = extractObject(blob, "\"manga\":{")
		val chaptersArray = findLargestChaptersArray(blob)
		val chapters = chaptersArray?.let { parseChapters(it, slug) }.orEmpty()

		val enriched = detail?.let { json ->
			manga.copy(
				coverUrl = json.optJSONObject("coverImage")?.getStringOrNull("large") ?: manga.coverUrl,
				title = json.optJSONObject("title")?.let {
					it.getStringOrNull("userPreferred") ?: it.getStringOrNull("english") ?: it.getStringOrNull("romaji")
				}?.takeIf { it.isNotBlank() } ?: manga.title,
				altTitles = json.optJSONObject("title")?.let {
					setOfNotNull(
						it.getStringOrNull("english"),
						it.getStringOrNull("romaji"),
						it.getStringOrNull("native"),
					)
				}.orEmpty().ifEmpty { manga.altTitles },
				description = json.getStringOrNull("description")
					?.takeIf { it.isNotBlank() && it != "null" }
					?.replace("<br>", "\n") ?: manga.description,
				tags = parseGenres(json).ifEmpty { manga.tags },
				state = parseStatus(json.getStringOrNull("status")) ?: manga.state,
				rating = json.optInt("averageScore", -1).takeIf { it >= 0 }
					?.let { (it / 100f).coerceIn(0f, 1f) } ?: manga.rating,
				authors = buildSet {
					addAll(parseStringArray(json.optJSONArray("authors")))
					addAll(parseStringArray(json.optJSONArray("artists")))
				}.ifEmpty { manga.authors },
			)
		} ?: manga

		return enriched.copy(chapters = chapters)
	}

	private fun parseChapters(chaptersArray: JSONArray, slug: String): List<MangaChapter> {
		val now = System.currentTimeMillis()
		val result = ArrayList<MangaChapter>(chaptersArray.length())
		val seen = HashSet<String>()
		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.optJSONObject(i) ?: continue
			val uuid = ch.getStringOrNull("id")?.takeIf { it.isNotBlank() } ?: continue
			if (!seen.add(uuid)) continue

			// Chapters still behind a timed paywall (premiumUntil in the future) can't be read.
			val premiumUntil = parseDate(ch.getStringOrNull("premiumUntil"))
			if (premiumUntil > now) continue

			val numberStr = ch.getStringOrNull("number")?.trim().orEmpty()
			val number = numberStr.toFloatOrNull() ?: -1f

			val rawTitle = ch.getStringOrNull("title")?.takeIf { it.isNotBlank() && it != "null" }
			val label = numberStr.ifEmpty { (i + 1).toString() }
			val title = when {
				rawTitle == null -> "Chapitre $label"
				rawTitle.startsWith("Chapitre", ignoreCase = true) || rawTitle.startsWith("Ép", ignoreCase = true) -> rawTitle
				else -> "Chapitre $label - $rawTitle"
			}

			val chapterUrl = "/read/$slug/$uuid"
			result.add(
				MangaChapter(
					id = generateUid(chapterUrl),
					title = title,
					number = if (number >= 0f) number else (i + 1).toFloat(),
					volume = ch.optInt("volume", 0).coerceAtLeast(0),
					url = chapterUrl,
					uploadDate = parseDate(ch.getStringOrNull("updatedAt")),
					source = source,
					scanlator = null,
					branch = null,
				),
			)
		}
		return result.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// The reader page server-renders every page image on cdn.onefy.me directly in the HTML
		// (as <img>/preload tags), not inside the __next_f RSC payload — so scan the raw HTML.
		// Each page appears as a full-size URL plus `w_<width>` resize variants; normalise those
		// back to the full-size form. The page may also reference a neighbouring chapter's cover,
		// so group by chapter folder (c<n>-<hash>) and keep the folder holding the most pages.
		val html = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseRaw()
		val folders = HashMap<String, LinkedHashMap<Int, String>>()
		for (match in pageImageRegex.findAll(html)) {
			val folder = match.groupValues[1]
			val pageNo = match.groupValues[2].toIntOrNull() ?: continue
			// The bare /t/manga/.../NNN.jpg "original" isn't stored on the CDN (it 404s with
			// "File not found"); only the /t/w_<width>/ resize variants exist. Normalise every
			// match to a single large width so each page resolves to a real image.
			val sized = match.value
				.replace(Regex("""/t/w_\d+/manga/"""), "/t/manga/")
				.replace("/t/manga/", "/t/w_$PAGE_WIDTH/manga/")
			folders.getOrPut(folder) { LinkedHashMap() }.putIfAbsent(pageNo, sized)
		}
		val pages = folders.values.maxByOrNull { it.size } ?: return emptyList()
		return pages.entries
			.sortedBy { it.key }
			.map { (_, url) ->
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = chapter.source,
				)
			}
	}

	// --- helpers ---

	private fun parseGenres(json: JSONObject): Set<MangaTag> {
		val out = LinkedHashSet<MangaTag>()
		for (key in arrayOf("genres", "tags")) {
			val arr = json.optJSONArray(key) ?: continue
			for (i in 0 until arr.length()) {
				val name = arr.optString(i).trim()
				if (name.isNotEmpty() && name != "null") {
					out.add(MangaTag(key = name.lowercase(sourceLocale), title = name, source = source))
				}
			}
		}
		return out
	}

	private fun parseStringArray(arr: JSONArray?): Set<String> {
		if (arr == null) return emptySet()
		val out = LinkedHashSet<String>()
		for (i in 0 until arr.length()) {
			val name = arr.optString(i).trim()
			if (name.isNotEmpty() && name != "null") out.add(name)
		}
		return out
	}

	private fun parseStatus(status: String?): MangaState? {
		return when (status?.trim()?.lowercase(sourceLocale)) {
			"releasing", "ongoing", "en cours" -> MangaState.ONGOING
			"finished", "completed", "terminé" -> MangaState.FINISHED
			"hiatus", "paused", "en pause" -> MangaState.PAUSED
			"cancelled", "canceled", "annulé", "abandonné" -> MangaState.ABANDONED
			"not_yet_released" -> MangaState.UPCOMING
			else -> null
		}
	}

	private fun parseDate(dateString: String?): Long {
		if (dateString.isNullOrBlank() || dateString == "null") return 0L
		return isoDateFormat.parseSafe(dateString.trim())
	}

	/**
	 * Finds the longest `"latestChapters":[...]` array in the decoded payload. On the detail page
	 * this key carries the full chapter list (id/number/updatedAt/premiumUntil), not just the
	 * latest ones; the homepage/related-manga copies are shorter, so the largest one wins.
	 */
	private fun findLargestChaptersArray(blob: String): JSONArray? {
		val marker = "\"latestChapters\":["
		var best: JSONArray? = null
		var searchIdx = 0
		while (true) {
			val at = blob.indexOf(marker, searchIdx)
			if (at == -1) break
			val arrStr = extractJsonArrayString(blob, at + marker.length - 1)
			if (arrStr != null) {
				try {
					val arr = JSONArray(arrStr)
					if (best == null || arr.length() > best.length()) best = arr
				} catch (_: Exception) {
					// ignore malformed
				}
				searchIdx = at + marker.length - 1 + arrStr.length
			} else {
				searchIdx = at + marker.length
			}
		}
		return best
	}

	private fun extractObject(blob: String, marker: String): JSONObject? {
		val at = blob.indexOf(marker)
		if (at == -1) return null
		val objStr = extractJsonObjectString(blob, at + marker.length - 1) ?: return null
		return try {
			JSONObject(objStr)
		} catch (_: Exception) {
			null
		}
	}

	private fun decodePayload(doc: Document): String {
		val sb = StringBuilder()
		for (script in doc.select("script")) {
			val data = script.data()
			if (!data.contains("__next_f.push")) continue
			for (match in nextFPushRegex.findAll(data)) {
				sb.append(match.groupValues[1])
			}
		}
		return unescape(sb.toString())
	}

	/** Single-level unescape of the JS string payload (equivalent to Python's unicode_escape). */
	private fun unescape(s: String): String {
		if (s.indexOf('\\') == -1) return s
		val sb = StringBuilder(s.length)
		var i = 0
		while (i < s.length) {
			val c = s[i]
			if (c == '\\' && i + 1 < s.length) {
				when (s[i + 1]) {
					'\\' -> { sb.append('\\'); i += 2 }
					'"' -> { sb.append('"'); i += 2 }
					'/' -> { sb.append('/'); i += 2 }
					'n' -> { sb.append('\n'); i += 2 }
					'r' -> { sb.append('\r'); i += 2 }
					't' -> { sb.append('\t'); i += 2 }
					'b' -> { sb.append('\b'); i += 2 }
					'f' -> { sb.append('\u000C'); i += 2 }
					'u' -> {
						val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else null
						val code = hex?.toIntOrNull(16)
						if (code != null) {
							sb.append(code.toChar()); i += 6
						} else {
							sb.append(c); i++
						}
					}
					else -> { sb.append(c); i++ }
				}
			} else {
				sb.append(c); i++
			}
		}
		return sb.toString()
	}

	private fun extractJsonObjectString(data: String, startIndex: Int): String? {
		if (startIndex < 0 || startIndex >= data.length || data[startIndex] != '{') return null
		var braceBalance = 1
		var inString = false
		var i = startIndex + 1
		while (i < data.length) {
			when (data[i]) {
				'\\' -> if (inString) i++
				'"' -> inString = !inString
				'{' -> if (!inString) braceBalance++
				'}' -> if (!inString) {
					braceBalance--
					if (braceBalance == 0) return data.substring(startIndex, i + 1)
				}
			}
			i++
		}
		return null
	}

	private fun extractJsonArrayString(data: String, startIndex: Int): String? {
		if (startIndex < 0 || startIndex >= data.length || data[startIndex] != '[') return null
		var balance = 1
		var inString = false
		var i = startIndex + 1
		while (i < data.length) {
			when (data[i]) {
				'\\' -> if (inString) i++
				'"' -> inString = !inString
				'[' -> if (!inString) balance++
				']' -> if (!inString) {
					balance--
					if (balance == 0) return data.substring(startIndex, i + 1)
				}
			}
			i++
		}
		return null
	}
}
