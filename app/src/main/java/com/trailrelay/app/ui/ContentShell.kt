package com.trailrelay.app.ui

import android.view.View
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.trailrelay.app.R

/** Shared list/detail shell. Only the app bar owns the top inset; the scroller owns the bottom. */
object ContentShell {
    fun install(activity: AppCompatActivity, title: Int, content: Int, scrollingView: Int,
                chrome: Int? = null) {
        activity.enableEdgeToEdge()
        activity.setContentView(if (chrome == null) R.layout.shell_content
            else R.layout.shell_content_collapsible_chrome)
        val root = activity.findViewById<View>(R.id.shell_root)
        val appBar = activity.findViewById<AppBarLayout>(R.id.shell_app_bar)
        val body = activity.findViewById<FrameLayout>(R.id.shell_body)
        val contentHost = if (chrome == null) body
            else activity.findViewById<FrameLayout>(R.id.shell_scroll_body)
        activity.layoutInflater.inflate(content, contentHost, true)
        chrome?.let { activity.layoutInflater.inflate(it,
            activity.findViewById<FrameLayout>(R.id.shell_chrome), true) }
        if (chrome == null) activity.findViewById<View>(R.id.shell_collapsing).apply {
            layoutParams = (layoutParams as AppBarLayout.LayoutParams).apply { scrollFlags = 0 }
        }
        activity.findViewById<MaterialToolbar>(R.id.shell_toolbar).apply {
            setTitle(title)
            setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        }
        val scroll = activity.findViewById<View>(scrollingView)
        val bottomPadding = scroll.paddingBottom
        WindowInsetsControllerCompat(activity.window, root).apply {
            val light = MaterialColors.isColorLight(MaterialColors.getColor(root,
                com.google.android.material.R.attr.colorSurface))
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.setPadding(bars.left, bars.top, bars.right, 0)
            body.setPadding(bars.left, 0, bars.right, 0)
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight,
                bottomPadding + maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
