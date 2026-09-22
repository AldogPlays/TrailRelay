package com.trailrelay.app.trails

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.trailrelay.app.R
import java.util.Locale

class MyTrailsActivity : AppCompatActivity() {
    private lateinit var model: TrailLibraryModel
    private val community = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::import)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_my_trails)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.library)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        model = ViewModelProvider(this)[TrailLibraryModel::class.java]
        findViewById<Button>(R.id.back_to_map).setOnClickListener { finish() }
        findViewById<Button>(R.id.import_gpx).setOnClickListener {
            picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        findViewById<ListView>(R.id.trail_list).setOnItemClickListener { _, _, position, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TRAIL_ID, model.trails[position].id))
            finish()
        }
        findViewById<Button>(R.id.community).setOnClickListener {
            community.launch(Intent(this, com.trailrelay.app.trails.community.CommunityActivity::class.java))
        }
        model.onChange = ::render
        render()
    }

    private fun render() {
        findViewById<Button>(R.id.import_gpx).isEnabled = !model.busy
        findViewById<View>(R.id.library_progress).visibility = if (model.busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.library_message).text = model.message
            ?: if (!model.busy && model.trails.isEmpty()) getString(R.string.no_trails) else ""
        findViewById<ListView>(R.id.trail_list).apply {
            isEnabled = !model.busy
            adapter = object : ArrayAdapter<Trail>(this@MyTrailsActivity,
                android.R.layout.simple_list_item_2, android.R.id.text1, model.trails) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    return super.getView(position, convertView, parent).apply {
                        val trail = getItem(position)!!
                        findViewById<TextView>(android.R.id.text1).text = trail.name
                        findViewById<TextView>(android.R.id.text2).apply {
                            text = listOfNotNull(String.format(Locale.getDefault(), "%.2f mi", trail.distanceMeters / 1609.344),
                                trail.description?.takeIf { it.isNotBlank() }).joinToString(" · ")
                            maxLines = 3
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onDestroy() {
        model.onChange = null
        super.onDestroy()
    }

    companion object { const val EXTRA_TRAIL_ID = "trailId" }
}
