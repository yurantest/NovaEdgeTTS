package com.novareader.edge_tts

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : android.app.Activity() {

    // Material Icons codepoints
    private companion object {
        const val ICON_REFRESH = "\uE5D5"
        const val ICON_SETTINGS = "\uE8B8"
        const val ICON_SEARCH = "\uE8B6"
        const val ICON_LOGS = "\uE873"
        const val ICON_DELETE = "\uE872"
        const val ICON_MIC = "\uE029"
        const val ICON_PLAY = "\uE037"
        const val ICON_STOP = "\uE047"
    }

    private lateinit var repo: EdgeVoiceRepository

    // ── Предзаписанные примеры голосов: assets/samples/<ShortName>.mp3 ──
    // Файлы делает tools/make_voice_samples.py; кнопка ▶ показывается только для голосов,
    // у которых файл есть. Проигрывание локальное — без сети и без Edge-сервиса.
    private val sampleNames: Set<String> by lazy {
        try { assets.list("samples")?.map { it.removeSuffix(".mp3") }?.toSet() ?: emptySet() }
        catch (_: Exception) { emptySet() }
    }
    private var samplePlayer: android.media.MediaPlayer? = null
    private var samplePlaying: String? = null

    private fun stopSample() {
        try { samplePlayer?.release() } catch (_: Exception) {}
        samplePlayer = null
        samplePlaying = null
    }

    /** Играет пример голоса; повторное нажатие на тот же голос — стоп. */
    private fun toggleSample(name: String) {
        val wasPlaying = samplePlaying == name
        stopSample()
        if (wasPlaying) { render(); return }
        try {
            val fd = assets.openFd("samples/$name.mp3")
            val mp = android.media.MediaPlayer()
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.setOnCompletionListener { stopSample(); render() }
            mp.setOnErrorListener { _, _, _ -> stopSample(); render(); true }
            mp.prepare()
            mp.start()
            samplePlayer = mp
            samplePlaying = name
        } catch (e: Exception) {
            FileLogger.log("Пример голоса $name не воспроизвёлся: ${e.javaClass.simpleName}")
            Toast.makeText(this, "Не удалось воспроизвести пример", Toast.LENGTH_SHORT).show()
        }
        render()
    }
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var chipChecked: TextView
    private var onlyChecked = false   // фильтр «Отмеченные»: показывать только отмеченные голоса
    private var voices = emptyList<EdgeVoice>()
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var icons: Typeface? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = getColor(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = EdgeVoiceRepository(this)
        FileLogger.init(this)
        FileLogger.log("MainActivity создана")
        icons = try {
            Typeface.createFromAsset(assets, "fonts/MaterialIcons-Regular.ttf")
        } catch (_: Exception) {
            null
        }
        buildUi()
        val cached = repo.loadCached()
        load(force = cached.isEmpty())
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.md_surface))
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        // ── Header ──────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleBlock.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(color(R.color.md_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleBlock.addView(TextView(this).apply {
            text = getString(R.string.app_subtitle)
            setTextColor(color(R.color.md_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleBlock)

        // Icon-only action buttons in header
        header.addView(iconButton(ICON_REFRESH, getString(R.string.cd_refresh)) { load(true) })
        header.addView(space(dp(8)))
        header.addView(iconButton(ICON_SETTINGS, getString(R.string.cd_settings)) { openTtsSettings() })
        root.addView(header)

        // ── How-to card (collapsible) ───────────────────────────
        root.addView(card {
            val howToBody = TextView(this@MainActivity).apply {
                text = getString(R.string.howto_body)
                setTextColor(color(R.color.md_on_surface_variant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            }

            val howToArrow = TextView(this@MainActivity).apply {
                text = "⌃"
                setTextColor(color(R.color.md_on_surface_variant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(2), 0)
            }

            val howToHeader = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val expanded = howToBody.visibility == View.VISIBLE
                    howToBody.visibility = if (expanded) View.GONE else View.VISIBLE
                    howToArrow.text = if (expanded) "⌄" else "⌃"
                }
            }

            howToHeader.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.howto_title)
                setTextColor(color(R.color.md_on_surface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            howToHeader.addView(howToArrow, LinearLayout.LayoutParams(dp(32), dp(32)))

            addView(howToHeader)
            addView(howToBody)
        })

        // ── Status + кнопка «Отмеченные» ────────────────────────
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        status = TextView(this).apply {
            text = getString(R.string.status_loading)
            setTextColor(color(R.color.md_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        statusRow.addView(status)
        chipChecked = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onlyChecked = !onlyChecked
                updateChip()
                render()
            }
        }
        statusRow.addView(chipChecked)
        root.addView(statusRow)
        updateChip()

        // ── Search field ────────────────────────────────────────
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.color.md_surface_card), dp(12))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            elevation = 1f * density
        }
        searchRow.addView(TextView(this).apply {
            text = ICON_SEARCH
            typeface = icons
            setTextColor(color(R.color.md_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, dp(8), 0)
        })
        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setHintTextColor(color(R.color.md_on_surface_variant))
            setTextColor(color(R.color.md_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(0, dp(10), 0, dp(10))
        }
        search.setOnEditorActionListener { _, _, _ -> render(); false }
        searchRow.addView(search)
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
            topMargin = dp(2)
        })

        // ── Voice list ──────────────────────────────────────────
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ── Bottom bar: logs ────────────────────────────────────
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        bottom.addView(textIconButton(ICON_LOGS, getString(R.string.logs)) { showLogs() })
        bottom.addView(space(dp(12)))
        bottom.addView(textIconButton(ICON_DELETE, getString(R.string.clear)) {
            FileLogger.clear()
            Toast.makeText(this@MainActivity, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
        })
        root.addView(bottom)

        setContentView(root)
    }

    // ── UI helpers ──────────────────────────────────────────────

    private fun space(w: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
    }

    private fun card(build: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.md_surface_card), dp(14))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = 2f * density
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(4)
            }
            build()
        }
    }

    /** Round icon button (Material style) */
    private fun iconButton(glyph: String, contentDesc: String, onClick: () -> Unit): TextView {
        val size = dp(42)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.color.md_surface_card))
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(color(R.color.md_primary) and 0x40FFFFFF or 0x40000000),
            bg,
            null
        )
        return TextView(this).apply {
            text = glyph
            typeface = icons
            setTextColor(color(R.color.md_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            contentDescription = contentDesc
            background = ripple
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** Chip-style button with icon + label */
    private fun textIconButton(glyph: String, label: String, onClick: () -> Unit): LinearLayout {
        val bg = rounded(color(R.color.md_surface_card), dp(20))
        val ripple = RippleDrawable(
            ColorStateList.valueOf(color(R.color.md_primary) and 0x33FFFFFF),
            bg,
            null
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple
            setPadding(dp(14), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = glyph
                typeface = icons
                setTextColor(color(R.color.md_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, 0, dp(6), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(color(R.color.md_on_surface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            try {
                startActivity(Intent("android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                status.text = getString(R.string.status_tts_settings_fail)
            }
        }
    }

    private fun showLogs() {
        val logContent = FileLogger.getLogContent()
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.logs_title))
            .setMessage(logContent)
            .setPositiveButton(getString(R.string.ok)) { d, _ -> d.dismiss() }
            .setNeutralButton(getString(R.string.logs_copy)) { d, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Nova TTS Logs", logContent))
                Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .create()
        dialog.show()
    }

    private fun load(force: Boolean) {
        status.text = if (force) getString(R.string.status_updating) else getString(R.string.status_loading)
        FileLogger.log("Загрузка голосов, force=$force")
        executor.execute {
            try {
                val data = if (force) repo.refresh() else repo.loadCached()
                FileLogger.log("Загружено ${data.size} голосов")

                if (repo.selected().isEmpty() && data.isNotEmpty()) {
                    val prefer = listOf(
                        "ru-RU-SvetlanaNeural",
                        "ru-RU-DmitryNeural",
                        "en-US-JennyNeural"
                    )
                    val auto = data.firstOrNull { it.name in prefer }?.let { setOf(it.name) }
                        ?: setOf(data.first().name)
                    repo.replaceSelected(auto)
                    FileLogger.log("Автовыбор голоса: $auto")
                }
                runOnUiThread {
                    voices = data
                    render()
                    updateChip()
                    status.text = getString(R.string.status_count, data.size, repo.selected().size)
                }
            } catch (e: Throwable) {
                FileLogger.error("Ошибка при загрузке голосов", e)
                runOnUiThread { status.text = getString(R.string.status_error, e.message ?: "") }
            }
        }
    }

    /** Кликабельное поле «Отмеченные: N»: нажатие включает/выключает фильтр списка. */
    private fun updateChip() {
        val n = repo.selected().size
        chipChecked.text = getString(R.string.chip_checked, n)
        if (onlyChecked) {
            chipChecked.background = rounded(color(R.color.md_primary), dp(18))
            chipChecked.setTextColor(color(R.color.md_on_primary))
        } else {
            chipChecked.background = GradientDrawable().apply {
                setColor(color(R.color.md_surface_card))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), color(R.color.md_primary))
            }
            chipChecked.setTextColor(color(R.color.md_primary))
        }
    }

    private fun render() {
        list.removeAllViews()
        val q = search.text.toString().trim().lowercase(Locale.ROOT)
        val selected = repo.selected()
        val filtered = voices.filter {
            (!onlyChecked || selected.contains(it.name)) &&
                (q.isEmpty() ||
                    it.name.lowercase().contains(q) ||
                    it.locale.lowercase().contains(q) ||
                    it.friendlyName.lowercase().contains(q))
        }

        if (filtered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (voices.isEmpty()) getString(R.string.status_empty_hint)
                else if (onlyChecked && q.isEmpty()) getString(R.string.status_none_checked)
                else getString(R.string.status_not_found)
                setTextColor(color(R.color.md_on_surface_variant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }

        filtered.forEach { v ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(color(R.color.md_surface_card), dp(12))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                elevation = 1f * density
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                }
            }

            val check = CheckBox(this).apply {
                isChecked = selected.contains(v.name)
                buttonTintList = ColorStateList.valueOf(color(R.color.md_primary))
                setOnCheckedChangeListener { _, checked ->
                    repo.setSelected(v.name, checked)
                    status.text = getString(R.string.status_count, voices.size, repo.selected().size)
                    updateChip()
                    FileLogger.log("Голос ${v.name}: ${if (checked) "выбран" else "снят"}")
                }
            }
            row.addView(check)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setPadding(dp(4), 0, 0, 0)
            }
            textCol.addView(TextView(this).apply {
                text = v.friendlyName
                setTextColor(color(R.color.md_on_surface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            textCol.addView(TextView(this).apply {
                text = v.locale
                setTextColor(color(R.color.md_on_surface_variant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            row.addView(textCol)

            // ▶ пример голоса (если предзаписан)
            if (v.name in sampleNames) {
                val playing = samplePlaying == v.name
                row.addView(iconButton(
                    if (playing) ICON_STOP else ICON_PLAY,
                    "Пример голоса ${v.friendlyName}"
                ) { toggleSample(v.name) })
            }

            // Gender: Unicode ♂ / ♀ (works without Material Icons font)
            val isFemale = v.gender.equals("Female", ignoreCase = true)
            val isMale = v.gender.equals("Male", ignoreCase = true)
            if (isFemale || isMale) {
                row.addView(TextView(this).apply {
                    text = if (isFemale) "\u2640" else "\u2642"  // ♀ / ♂
                    // system sans-serif — not Material Icons
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(if (isFemale) 0xFFE91E63.toInt() else 0xFF42A5F5.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    contentDescription = v.gender
                    setPadding(dp(10), 0, dp(6), 0)
                })
            }

            // Tap whole row toggles checkbox
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { check.isChecked = !check.isChecked }

            list.addView(row)
        }
    }

    override fun onDestroy() {
        stopSample()
        tts?.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }
}
