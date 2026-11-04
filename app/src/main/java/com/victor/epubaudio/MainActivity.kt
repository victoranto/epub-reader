package com.victor.epubaudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tts: TtsEngine
    private var ttsReady = false

    private var book: Book? = null
    private var chunks: List<List<String>> = emptyList()

    // Estado de reproducción
    private var currentChapter = 0
    private var currentChunk = 0
    private var playing = false
    private var rate = 1.0f

    private var exportJob: Job? = null

    private lateinit var txtTitle: TextView
    private lateinit var txtNow: TextView
    private lateinit var list: ListView
    private lateinit var btnPlay: Button
    private lateinit var btnExport: Button
    private lateinit var progress: ProgressBar
    private lateinit var txtStatus: TextView

    private val openEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadBook(uri)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startExport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtTitle = findViewById(R.id.txtTitle)
        txtNow = findViewById(R.id.txtNow)
        list = findViewById(R.id.listChapters)
        btnPlay = findViewById(R.id.btnPlay)
        btnExport = findViewById(R.id.btnExport)
        progress = findViewById(R.id.progress)
        txtStatus = findViewById(R.id.txtStatus)
        val txtSpeed = findViewById<TextView>(R.id.txtSpeed)

        tts = TtsEngine(this)
        tts.onSpeakStart = { id -> runOnUiThread { onChunkStarted(id) } }
        tts.onSpeakDone = { id -> runOnUiThread { onChunkDone(id) } }
        lifecycleScope.launch {
            ttsReady = tts.init()
            if (!ttsReady) toast("No se pudo iniciar el motor de voz del móvil")
            book?.let { tts.setLanguage(it.language) }
            tts.setRate(rate)
        }

        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            openEpub.launch(arrayOf("application/epub+zip", "application/octet-stream"))
        }
        btnPlay.setOnClickListener { if (playing) pause() else play() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { jumpTo(currentChapter - 1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { jumpTo(currentChapter + 1) }
        list.setOnItemClickListener { _, _, position, _ -> jumpTo(position, autoplay = true) }

        findViewById<SeekBar>(R.id.seekSpeed).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                rate = 0.5f + p * 0.1f
                txtSpeed.text = "Velocidad: %.1fx".format(rate)
                tts.setRate(rate)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (playing) { pause(); play() } // aplica la nueva velocidad
            }
        })

        btnExport.setOnClickListener {
            if (exportJob?.isActive == true) {
                exportJob?.cancel()
            } else if (book == null) {
                toast("Abre primero un EPUB")
            } else {
                toast("Elige la carpeta donde guardar el audiolibro")
                pickFolder.launch(null)
            }
        }

        intent?.data?.let { loadBook(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { loadBook(it) }
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    // ---------- Carga del libro ----------

    private fun loadBook(uri: Uri) {
        pause()
        txtStatus.text = "Leyendo EPUB…"
        lifecycleScope.launch {
            try {
                val b = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { EpubParser.parse(it) }
                }
                book = b
                chunks = b.chapters.map { EpubParser.chunk(it.text, tts.maxInput) }
                currentChapter = 0
                currentChunk = 0
                txtTitle.text = b.title
                list.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_activated_1,
                    b.chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" }
                )
                list.setItemChecked(0, true)
                if (ttsReady) tts.setLanguage(b.language)
                txtStatus.text = "${b.chapters.size} capítulos"
                updateNow()
            } catch (e: Exception) {
                txtStatus.text = "Error: ${e.message}"
            }
        }
    }

    // ---------- Reproducción ----------

    private fun play() {
        val b = book ?: return toast("Abre primero un EPUB")
        if (!ttsReady) return toast("El motor de voz aún no está listo")
        if (exportJob?.isActive == true) return toast("Espera a que termine la exportación")
        if (currentChapter >= b.chapters.size) return
        playing = true
        btnPlay.text = "⏸ Pausa"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val parts = chunks[currentChapter]
        for (i in currentChunk until parts.size) {
            tts.speak(parts[i], "p_${currentChapter}_$i", flush = i == currentChunk)
        }
    }

    private fun pause() {
        playing = false
        if (::btnPlay.isInitialized) btnPlay.text = "▶ Escuchar"
        if (::tts.isInitialized) tts.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun jumpTo(chapter: Int, autoplay: Boolean = playing) {
        val b = book ?: return
        if (chapter !in b.chapters.indices) return
        pause()
        currentChapter = chapter
        currentChunk = 0
        list.setItemChecked(chapter, true)
        list.smoothScrollToPosition(chapter)
        updateNow()
        if (autoplay) play()
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val p = id.split('_')
        if (p.size != 3 || p[0] != "p") return null
        return p[1].toInt() to p[2].toInt()
    }

    private fun onChunkStarted(id: String) {
        val (ch, i) = parseId(id) ?: return
        if (ch == currentChapter) {
            currentChunk = i
            updateNow()
        }
    }

    private fun onChunkDone(id: String) {
        val (ch, i) = parseId(id) ?: return
        if (!playing || ch != currentChapter) return
        if (i == chunks[ch].size - 1) {
            // Fin del capítulo: pasamos al siguiente automáticamente
            if (ch + 1 < chunks.size) jumpTo(ch + 1, autoplay = true) else pause()
        }
    }

    private fun updateNow() {
        val b = book ?: return
        val total = chunks.getOrNull(currentChapter)?.size ?: 0
        txtNow.text = "Capítulo ${currentChapter + 1}/${b.chapters.size} · parte ${currentChunk + 1}/$total"
    }

    // ---------- Exportación ----------

    private fun startExport(treeUri: Uri) {
        val b = book ?: return
        pause()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progress.visibility = View.VISIBLE
        progress.max = chunks.sumOf { it.size }
        progress.progress = 0
        btnExport.text = "Cancelar exportación"

        exportJob = lifecycleScope.launch {
            val work = File(cacheDir, "export").apply { deleteRecursively(); mkdirs() }
            try {
                val folder = withContext(Dispatchers.IO) { createBookFolder(treeUri, safeName(b.title)) }
                for ((ci, chapter) in b.chapters.withIndex()) {
                    txtStatus.text = "Generando capítulo ${ci + 1}/${b.chapters.size}: ${chapter.title}"
                    val wavs = ArrayList<File>()
                    for ((pi, part) in chunks[ci].withIndex()) {
                        if (!isActive) return@launch
                        val wav = File(work, "c${ci}_$pi.wav")
                        val ok = tts.synthesizeToFile(part, wav, "x_${ci}_$pi")
                        if (ok && wav.length() > 44) wavs.add(wav)
                        progress.progress += 1
                    }
                    if (wavs.isEmpty()) continue
                    txtStatus.text = "Codificando capítulo ${ci + 1}/${b.chapters.size}…"
                    val fileName = "%02d - %s.m4a".format(ci + 1, safeName(chapter.title))
                    withContext(Dispatchers.IO) {
                        val pcm = File(work, "chapter.pcm")
                        val m4a = File(work, "chapter.m4a")
                        val info = AudioEncoder.concatWavs(wavs, pcm)
                        AudioEncoder.encodeToM4a(pcm, info, m4a)
                        copyToFolder(m4a, folder, fileName)
                        wavs.forEach { it.delete() }
                        pcm.delete(); m4a.delete()
                    }
                }
                txtStatus.text = "¡Listo! Audiolibro guardado en la carpeta \"${safeName(b.title)}\""
            } catch (e: Exception) {
                txtStatus.text = "Error al exportar: ${e.message}"
            } finally {
                if (!isActive) txtStatus.text = "Exportación cancelada"
                work.deleteRecursively()
                progress.visibility = View.GONE
                btnExport.text = "Exportar audiolibro (.m4a por capítulo)"
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun createBookFolder(treeUri: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        return DocumentsContract.createDocument(
            contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IllegalStateException("No se pudo crear la carpeta")
    }

    private fun copyToFolder(file: File, folder: Uri, name: String) {
        val doc = DocumentsContract.createDocument(contentResolver, folder, "audio/mp4", name)
            ?: throw IllegalStateException("No se pudo crear $name")
        contentResolver.openOutputStream(doc)!!.use { out ->
            FileInputStream(file).use { it.copyTo(out) }
        }
    }

    private fun safeName(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifEmpty { "Audiolibro" }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
