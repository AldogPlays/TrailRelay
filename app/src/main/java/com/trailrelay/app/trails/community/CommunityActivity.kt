package com.trailrelay.app.trails.community

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.trailrelay.app.R
import com.trailrelay.app.trails.MyTrailsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class CommunityActivity : AppCompatActivity() {
    private lateinit var model: CommunityModel
    private var visibleEntries = emptyList<CatalogEntry>()
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_community)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.community_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        model = ViewModelProvider(this)[CommunityModel::class.java]
        findViewById<Button>(R.id.community_back).setOnClickListener { finish() }
        findViewById<EditText>(R.id.community_search).apply {
            setText(model.query)
            doAfterTextChanged { model.query = it.toString(); renderList() }
        }
        findViewById<ListView>(R.id.community_list).setOnItemClickListener { _, _, position, _ ->
            showDetails(visibleEntries[position])
        }
        model.onChange = ::render
        render()
        model.selected?.let(::showDetails)
    }

    private fun filter(id: Int, label: String, values: List<String>, selected: String?, update: (String?) -> Unit) {
        val spinner = findViewById<Spinner>(id)
        val options = listOf(label) + (values + listOfNotNull(selected)).distinct().sortedBy { it.lowercase() }
        if (spinner.tag == options) return
        spinner.onItemSelectedListener = null
        spinner.tag = options
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(selected?.let(options::indexOf)?.coerceAtLeast(0) ?: 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, rowId: Long) {
                update(if (position == 0) null else options[position]); renderList()
            }
        }
    }

    private fun render() {
        model.downloadedId?.let {
            setResult(RESULT_OK, Intent().putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, it))
            finish()
            return
        }
        filter(R.id.state_filter, "All states", model.entries.mapNotNull { it.state }, model.state) { model.state = it }
        filter(R.id.difficulty_filter, "All difficulties", model.entries.mapNotNull { it.difficulty }, model.difficulty) { model.difficulty = it }
        filter(R.id.vehicle_filter, "All vehicle types", VEHICLE_TYPES + model.entries.flatMap { it.vehicleTypes.orEmpty() }, model.vehicle) { model.vehicle = it }
        findViewById<LinearProgressIndicator>(R.id.community_progress).visibility =
            if (model.refreshing || model.downloading) View.VISIBLE else View.GONE
        findViewById<ListView>(R.id.community_list).isEnabled = !model.downloading
        renderList()
    }

    private fun renderList() {
        visibleEntries = CommunityCatalog.filter(model.entries, model.query, model.state, model.difficulty, model.vehicle)
        findViewById<TextView>(R.id.community_message).text = model.message + when {
            model.refreshing && visibleEntries.isEmpty() -> ""
            visibleEntries.isEmpty() && model.entries.isNotEmpty() -> "\n${getString(R.string.no_matching_trails)}"
            visibleEntries.isEmpty() -> "\n${getString(R.string.no_community_trails)}"
            else -> "\n${resources.getQuantityString(R.plurals.trail_count, visibleEntries.size, visibleEntries.size)}"
        }
        findViewById<ListView>(R.id.community_list).adapter = object : ArrayAdapter<CatalogEntry>(this,
            R.layout.trail_list_item, R.id.trail_row_name, visibleEntries) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    val entry = getItem(position)!!
                    findViewById<TextView>(R.id.trail_row_name).text = entry.name
                    findViewById<TextView>(R.id.trail_row_meta).text =
                        listOfNotNull(entry.distanceMiles?.let { "%.1f mi".format(it) },
                            entry.difficulty, getString(if (entry.id in model.savedRemoteIds)
                                R.string.in_my_trails else R.string.available_to_download)).joinToString(" · ")
                    findViewById<TextView>(R.id.trail_row_detail).apply {
                        text = listOfNotNull(entry.state, entry.region).joinToString(" · ")
                        visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                    }
                }
        }
    }

    private fun showDetails(entry: CatalogEntry) {
        model.selected = entry
        val details = listOfNotNull(
            entry.description, "Catalog ID: ${entry.id}",
            entry.state?.let { "State: $it" }, entry.region?.let { "Region: $it" },
            entry.difficulty?.let { "Difficulty: $it" },
            entry.vehicleTypes?.let { "Vehicle types: ${it.joinToString(", ")}" },
            entry.tags?.let { "Tags: ${it.joinToString(", ")}" },
            entry.distanceMiles?.let { "Catalog distance: $it mi" },
            entry.updatedAt?.let { "Updated: $it" }, "GPX: ${entry.gpxUrl}"
        ).joinToString("\n\n")
        dialog = MaterialAlertDialogBuilder(this).setTitle(entry.name).setMessage(details)
            .setPositiveButton(if (entry.id in model.savedRemoteIds) R.string.update_trail else R.string.download_trail) { _, _ ->
                model.selected = null; model.download(entry)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.selected = null }
            .setOnCancelListener { model.selected = null }.show()
    }

    override fun onDestroy() {
        model.onChange = null
        dialog?.dismiss()
        super.onDestroy()
    }
}
