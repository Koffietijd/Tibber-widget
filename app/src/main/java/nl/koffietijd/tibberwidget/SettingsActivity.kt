package nl.koffietijd.tibberwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Instellingen: API-token invullen, aanpassen of verwijderen en (bij meerdere adressen) de woning
 * kiezen. Wordt ook automatisch geopend wanneer je de widget toevoegt.
 */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var tokenInput: EditText
    private lateinit var status: TextView
    private lateinit var homeLabel: TextView
    private lateinit var homeSpinner: Spinner
    private lateinit var deleteButton: Button
    private var homes: List<HomeRef> = emptyList()
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // De widget altijd laten plaatsen, ook als je terug drukt.
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            if (prefs.token != null) {
                RefreshWorker.schedule(this)
                RefreshWorker.refreshNow(this)
            }
        }

        buildUi()
        refreshUiState()
        if (prefs.token != null) loadHomes(prefs.token!!, showSuccess = false)
    }

    override fun finish() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Nieuwe widget direct tekenen (onUpdate wordt na configuratie niet aangeroepen).
            runCatching { WidgetUpdater.updateAll(this) }
        }
        super.finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(24), px(20), px(24))
        }
        fun title(text: String) = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(16), 0, px(8))
        }
        fun body(text: String) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, px(4), 0, px(4))
        }

        root.addView(TextView(this).apply {
            text = "⚡ Tibber Widget"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(title("Tibber API-token"))
        root.addView(body("Maak een persoonlijk token aan op developer.tibber.com → Settings → Access token en plak het hieronder."))
        root.addView(Button(this).apply {
            text = "Token aanmaken op developer.tibber.com"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.tibber.com/settings/access-token")))
            }
        })

        tokenInput = EditText(this).apply {
            hint = "Plak hier je API-token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(prefs.token.orEmpty())
        }
        root.addView(tokenInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(CheckBox(this).apply {
            text = "Token tonen"
            setOnCheckedChangeListener { _, checked ->
                tokenInput.inputType = InputType.TYPE_CLASS_TEXT or
                    if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                tokenInput.setSelection(tokenInput.text.length)
            }
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Opslaan & testen"
            isAllCaps = false
            setOnClickListener { saveToken() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        deleteButton = Button(this).apply {
            text = "Verwijderen"
            isAllCaps = false
            setOnClickListener { deleteToken() }
        }
        buttons.addView(deleteButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, px(8), 0, px(8))
            movementMethod = LinkMovementMethod.getInstance()
        }
        root.addView(status)

        homeLabel = title("Woning").apply { visibility = View.GONE }
        root.addView(homeLabel)
        homeSpinner = Spinner(this).apply { visibility = View.GONE }
        root.addView(homeSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "Widget nu verversen"
            isAllCaps = false
            setOnClickListener {
                if (prefs.token == null) {
                    setStatus("Vul eerst een API-token in.", error = true)
                } else {
                    RefreshWorker.refreshNow(this@SettingsActivity)
                    setStatus("Verversen gestart. De widget wordt over enkele seconden bijgewerkt.")
                }
            }
        })

        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            root.addView(Button(this).apply {
                text = "Klaar"
                isAllCaps = false
                setOnClickListener { finish() }
            })
        }

        root.addView(title("Widget gebruiken"))
        root.addView(body(
            "1. Houd je startscherm ingedrukt → Widgets → Tibber Widget → sleep 'Tibber dashboard' naar het scherm.\n" +
                "2. Maak de widget zo groot als het hele scherm (lang indrukken → randen verslepen).\n" +
                "3. De widget ververst automatisch elke 15 minuten. Tik op de widget om direct te verversen.\n\n" +
                "Tips voor een telefoon aan de muur:\n" +
                "• Ontwikkelaarsopties → 'Stand-by blijven' (scherm blijft aan tijdens opladen).\n" +
                "• Zet batterijoptimalisatie uit voor Tibber Widget (Instellingen → Apps → Tibber Widget → Batterij → Onbeperkt).\n" +
                "• Live verbruik verschijnt automatisch als je een Tibber Pulse hebt."
        ))

        root.addView(TextView(this).apply {
            text = "Je token wordt alleen op dit toestel opgeslagen en alleen naar api.tibber.com gestuurd."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, px(24), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun refreshUiState() {
        deleteButton.isEnabled = prefs.token != null
        if (prefs.token == null) setStatus("Nog geen token ingesteld.")
    }

    private fun saveToken() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            setStatus("Vul een API-token in.", error = true)
            return
        }
        loadHomes(token, showSuccess = true, save = true)
    }

    private fun loadHomes(token: String, showSuccess: Boolean, save: Boolean = false) {
        if (showSuccess) setStatus("Token controleren…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { TibberApi.fetchViewer(token) } }
            result.onSuccess { viewer ->
                if (save) {
                    val changed = prefs.token != token
                    prefs.token = token
                    if (changed) {
                        prefs.homeId = null
                        prefs.cachedViewer = null
                        prefs.cachedLive = null
                    }
                    prefs.cachedViewer = viewer.toString()
                    prefs.fetchedAt = System.currentTimeMillis()
                    prefs.lastError = null
                    RefreshWorker.schedule(this@SettingsActivity, replace = true)
                    RefreshWorker.refreshNow(this@SettingsActivity)
                }
                homes = Parser.homeRefs(viewer)
                val home = Parser.pickHome(viewer, prefs.homeId)
                if (home != null && prefs.homeId == null && save) prefs.homeId = home.id
                showHomes()
                if (showSuccess) {
                    setStatus(
                        if (home != null) "✓ Verbonden met Tibber (${home.name}). De widget wordt bijgewerkt."
                        else "Token werkt, maar er is geen woning gevonden in je Tibber-account."
                    )
                }
                refreshUiState()
                withContext(Dispatchers.Default) { WidgetUpdater.updateAll(this@SettingsActivity) }
            }.onFailure { e ->
                if (showSuccess) setStatus("✗ ${e.message ?: "Verbinding mislukt"}", error = true)
            }
        }
    }

    private fun showHomes() {
        if (homes.size <= 1) {
            homeLabel.visibility = View.GONE
            homeSpinner.visibility = View.GONE
            return
        }
        homeLabel.visibility = View.VISIBLE
        homeSpinner.visibility = View.VISIBLE
        homeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, homes.map { it.label })
        val idx = homes.indexOfFirst { it.id == prefs.homeId }.coerceAtLeast(0)
        homeSpinner.setSelection(idx)
        homeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = homes[position].id
                if (chosen != prefs.homeId) {
                    prefs.homeId = chosen
                    RefreshWorker.refreshNow(this@SettingsActivity)
                    scope.launch(Dispatchers.Default) { WidgetUpdater.updateAll(this@SettingsActivity) }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun deleteToken() {
        prefs.clearAll()
        RefreshWorker.cancel(this)
        tokenInput.setText("")
        homes = emptyList()
        showHomes()
        refreshUiState()
        setStatus("Token en opgeslagen gegevens verwijderd.")
        scope.launch(Dispatchers.Default) { WidgetUpdater.updateAll(this@SettingsActivity) }
    }

    private fun setStatus(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) Color.parseColor("#F0524F") else Color.parseColor("#3FB6FF"))
    }
}
