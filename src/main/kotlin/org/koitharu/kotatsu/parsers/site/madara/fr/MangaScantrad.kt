package org.koitharu.kotatsu.parsers.site.madara.fr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGA_SCANTRAD", "MangaScantrad.io", "fr")
internal class MangaScantrad(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGA_SCANTRAD, "manga-scantrad.io") {
	override val datePattern = "d MMMM yyyy"

	// The site sits behind Cloudflare. Loading the catalogue through admin-ajax.php makes the
	// challenge WebView render WordPress' bare "0" response (and the POST itself returns "0"),
	// so the list stays empty until a plain page request sets the clearance cookie. Fetching the
	// list as a normal page GET (/page/N/?s=&post_type=wp-manga) avoids both problems.
	override val withoutAjax = true
}
