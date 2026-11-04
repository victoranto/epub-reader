package com.victor.epubaudio

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

data class Chapter(val title: String, val text: String)

data class Book(val title: String, val language: String?, val chapters: List<Chapter>)

/** Lee un EPUB (zip) y devuelve el texto de cada capítulo en el orden de lectura (spine). */
object EpubParser {

    private val textExtensions = listOf(".xml", ".opf", ".xhtml", ".html", ".htm", ".ncx")

    fun parse(input: InputStream): Book {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && textExtensions.any { name.lowercase().endsWith(it) }) {
                    files[name] = zip.readBytes()
                }
            }
        }

        val container = files["META-INF/container.xml"]
            ?: throw IllegalArgumentException("No parece un EPUB válido (falta META-INF/container.xml)")
        val containerDoc = Jsoup.parse(String(container, Charsets.UTF_8), "", Parser.xmlParser())
        val opfPath = containerDoc.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?: throw IllegalArgumentException("EPUB sin rootfile")
        val opfBytes = files[opfPath] ?: throw IllegalArgumentException("No se encuentra $opfPath")
        val opf = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())
        val opfDir = opfPath.substringBeforeLast('/', "")

        val bookTitle = firstTagText(opf, "dc:title", "title") ?: "Sin título"
        val language = firstTagText(opf, "dc:language", "language")

        val manifest = HashMap<String, String>()
        for (item in opf.getElementsByTag("item")) {
            manifest[item.attr("id")] = item.attr("href")
        }

        val chapters = ArrayList<Chapter>()
        for (ref in opf.getElementsByTag("itemref")) {
            if (ref.attr("linear") == "no") continue
            val href = manifest[ref.attr("idref")] ?: continue
            val path = resolve(opfDir, href)
            val bytes = files[path] ?: continue
            val html = Jsoup.parse(String(bytes, Charsets.UTF_8))
            val text = extractText(html)
            if (text.length < 20) continue // portadas, páginas vacías...
            val heading = html.select("h1, h2, h3").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
                ?: html.title().takeIf { it.isNotBlank() }
                ?: "Capítulo ${chapters.size + 1}"
            chapters.add(Chapter(heading.take(80), text))
        }
        if (chapters.isEmpty()) throw IllegalArgumentException("No se ha encontrado texto en el EPUB")
        return Book(bookTitle, language, chapters)
    }

    private fun firstTagText(doc: Document, vararg tags: String): String? {
        for (t in tags) {
            val v = doc.getElementsByTag(t).firstOrNull()?.text()?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    private fun resolve(baseDir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
        for (p in clean.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun extractText(doc: Document): String {
        val body = doc.body() ?: return ""
        val blocks = body.select("p, h1, h2, h3, h4, h5, h6, li, blockquote")
            .filter { el -> el.parents().none { it.tagName() in setOf("p", "li", "blockquote") } }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val text = if (blocks.isNotEmpty()) blocks.joinToString("\n") else body.text()
        return text.trim()
    }

    /** Divide un texto en trozos que el motor de voz pueda procesar, cortando por frases. */
    fun chunk(text: String, maxLen: Int): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        val sentences = text.split(Regex("(?<=[.!?…:;])\\s+|\\n+"))
        for (raw in sentences) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (sb.length + s.length + 1 > maxLen && sb.isNotEmpty()) {
                result.add(sb.toString()); sb.clear()
            }
            if (s.length > maxLen) {
                s.chunked(maxLen).forEach { result.add(it) }
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }
}
