package com.trailrelay.app.ui

import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.trailrelay.app.R

/** Map overlays own insets; the map itself remains edge-to-edge. */
class MapShell(activity: AppCompatActivity) {
    private val window: Window = activity.window
    private val root = activity.findViewById<View>(R.id.main)
    private val top = activity.findViewById<View>(R.id.map_top_controls)
    private val sheet = activity.findViewById<FrameLayout>(R.id.selection_card)
    private val summary = activity.findViewById<View>(R.id.selection_summary)
    private val expanded = activity.findViewById<View>(R.id.selection_expanded)
    private val content = activity.findViewById<LinearLayout>(R.id.selection_content)
    private val scroll = activity.findViewById<View>(R.id.selection_scroll)
    private val locationControls = activity.findViewById<LinearLayout>(R.id.location_controls)
    private val mapControl = activity.findViewById<ImageButton>(R.id.map_layers)
    private val speedHud = activity.findViewById<View>(R.id.speed_hud)
    private val expandButton = activity.findViewById<ImageButton>(R.id.selection_expand)
    private val controller = WindowInsetsControllerCompat(window, root)
    private val behavior = BottomSheetBehavior.from(sheet)
    private val spacing = activity.resources.getDimensionPixelSize(R.dimen.space_content)
    private val gap = activity.resources.getDimensionPixelSize(R.dimen.space_related)
    private val surfaceColor = MaterialColors.getColor(root, MaterialR.attr.colorSurface)
    private var navigationInset = 0
    private var statusInset = 0
    private var selectionVisible = false
    private var statusSurfaceShown = false
    private val mapControls = listOf<View>(mapControl, locationControls)

    init {
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        window.navigationBarColor = Color.TRANSPARENT
        behavior.isFitToContents = true
        behavior.isHideable = false
        behavior.isDraggable = true
        behavior.skipCollapsed = false
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        expanded.visibility = View.GONE
                        updateExpandAffordance(false)
                        placeControlsAboveCollapsedSheet()
                        updateStatusBarSurface(false)
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        expanded.visibility = View.VISIBLE
                        updateExpandAffordance(true)
                        updateMapControlVisibility()
                        updateStatusBarSurface(true)
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (!selectionVisible) return
                expanded.visibility = if (slideOffset > 0.01f) View.VISIBLE else View.GONE
                updateMapControlVisibility()
                if (slideOffset > 0f) {
                    updateStatusBarSurface(bottomSheet.top <= statusInset)
                }
            }
        })
        summary.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (selectionVisible) root.post(::refreshContentHeight)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            statusInset = bars.top
            navigationInset = bars.bottom
            top.layoutParams = (top.layoutParams as CoordinatorLayout.LayoutParams).apply {
                leftMargin = spacing + bars.left
                rightMargin = spacing + bars.right
                topMargin = spacing + bars.top
            }
            speedHud.layoutParams = (speedHud.layoutParams as CoordinatorLayout.LayoutParams).apply {
                rightMargin = spacing + bars.right
                topMargin = spacing + bars.top
            }
            locationControls.layoutParams = (locationControls.layoutParams as CoordinatorLayout.LayoutParams).apply {
                rightMargin = spacing + bars.right
                bottomMargin = if (selectionVisible) collapsedControlsBottomMargin() else spacing + bars.bottom
            }
            mapControl.layoutParams = (mapControl.layoutParams as CoordinatorLayout.LayoutParams).apply {
                leftMargin = spacing + bars.left
                bottomMargin = if (selectionVisible) collapsedControlsBottomMargin() else spacing + bars.bottom
            }
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, navigationInset)
            val protection = activity.resources.getDimensionPixelSize(R.dimen.space_section)
            activity.findViewById<View>(R.id.map_system_scrim).apply {
                layoutParams = layoutParams.apply { height = bars.top + protection }
            }
            activity.findViewById<View>(R.id.map_navigation_scrim).apply {
                layoutParams = layoutParams.apply { height = bars.bottom + protection }
            }
            if (selectionVisible) root.post(::refreshContentHeight)
            insets
        }
        locationControls.layoutParams = (locationControls.layoutParams as CoordinatorLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun setSelectionVisible(visible: Boolean, resetCollapsed: Boolean = false) {
        if (selectionVisible == visible) {
            if (visible && resetCollapsed) collapseSelection()
            return
        }
        selectionVisible = visible
        if (visible) {
            sheet.visibility = View.VISIBLE
            setNavigationSurface(true)
            updateMapControlVisibility()
            refreshContentHeight()
            sheet.post(::collapseSelection)
        } else {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            expanded.visibility = View.GONE
            sheet.visibility = View.GONE
            locationControls.layoutParams = (locationControls.layoutParams as CoordinatorLayout.LayoutParams).apply {
                bottomMargin = spacing + navigationInset
            }
            mapControl.layoutParams = (mapControl.layoutParams as CoordinatorLayout.LayoutParams).apply {
                bottomMargin = spacing + navigationInset
            }
            updateMapControlVisibility()
            setNavigationSurface(false)
            updateStatusBarSurface(false)
        }
    }

    fun toggleExpanded() {
        if (!selectionVisible) return
        behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED)
            BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
    }

    fun refreshContentHeight() {
        if (!selectionVisible) return
        if (root.width == 0 || root.height == 0) {
            root.doOnLayout { refreshContentHeight() }
            return
        }
        val previousVisibility = expanded.visibility
        expanded.visibility = View.VISIBLE
        content.measure(MeasureSpec.makeMeasureSpec(root.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val contentHeight = content.measuredHeight
        expanded.visibility = previousVisibility
        val usableHeight = (root.height - statusInset - navigationInset).coerceAtLeast(1)
        val maximumHeight = (usableHeight * MAX_SHEET_FRACTION).toInt().coerceAtLeast(1)
        val desiredHeight = (contentHeight + navigationInset).coerceAtMost(maximumHeight)
        val params = sheet.layoutParams as CoordinatorLayout.LayoutParams
        if (params.height != desiredHeight) {
            params.height = desiredHeight
            sheet.layoutParams = params
        }
        updatePeekHeight()
    }

    private fun collapseSelection() {
        if (!selectionVisible) return
        if (sheet.height == 0) {
            sheet.doOnLayout { collapseSelection() }
            return
        }
        updatePeekHeight()
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        if (behavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
            expanded.visibility = View.GONE
            updateExpandAffordance(false)
            placeControlsAboveCollapsedSheet()
        }
    }

    private fun updatePeekHeight() {
        val summaryHeight = maxOf(summary.height, summary.measuredHeight)
        if (!selectionVisible || summaryHeight == 0) return
        behavior.peekHeight = (summaryHeight + navigationInset).coerceAtMost(
            (sheet.layoutParams as CoordinatorLayout.LayoutParams).height)
        placeControlsAboveCollapsedSheet()
    }

    private fun placeControlsAboveCollapsedSheet() {
        if (!selectionVisible) return
        val margin = collapsedControlsBottomMargin()
        val locationParams = locationControls.layoutParams as CoordinatorLayout.LayoutParams
        if (locationParams.bottomMargin != margin) {
            locationParams.bottomMargin = margin
            locationControls.layoutParams = locationParams
        }
        val mapParams = mapControl.layoutParams as CoordinatorLayout.LayoutParams
        if (mapParams.bottomMargin != margin) {
            mapParams.bottomMargin = margin
            mapControl.layoutParams = mapParams
        }
        updateMapControlVisibility()
    }

    private fun updateMapControlVisibility() {
        val alpha = if (!selectionVisible) 1f else if (root.height == 0) 0f else {
            val controlBottomMargin = minOf(
                (locationControls.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin,
                (mapControl.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
            val clearance = sheet.top - (root.height - controlBottomMargin)
            // Use the collapsed gap as the fade range, so controls vanish before overlap.
            (clearance.toFloat() / gap.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        mapControls.forEach {
            it.alpha = alpha
            it.visibility = if (alpha <= 0.05f) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun collapsedControlsBottomMargin(): Int {
        val peekHeight = behavior.peekHeight.takeIf { it >= 0 }
            ?: (maxOf(summary.height, summary.measuredHeight) + navigationInset)
        return (peekHeight + gap).coerceAtLeast(navigationInset + spacing)
    }

    private fun updateExpandAffordance(expanded: Boolean) {
        expandButton.apply {
            rotation = if (expanded) 180f else 0f
            contentDescription = context.getString(
                if (expanded) R.string.collapse_trail_details else R.string.expand_trail_details)
        }
    }

    private fun setNavigationSurface(visible: Boolean) {
        window.navigationBarColor = if (visible) surfaceColor else Color.TRANSPARENT
        val light = visible && MaterialColors.isColorLight(surfaceColor)
        controller.isAppearanceLightNavigationBars = light
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = !visible
        }
    }

    private fun updateStatusBarSurface(visible: Boolean) {
        if (statusSurfaceShown == visible) return
        statusSurfaceShown = visible
        controller.isAppearanceLightStatusBars = visible && MaterialColors.isColorLight(surfaceColor)
    }

    private companion object { const val MAX_SHEET_FRACTION = 0.5f }
}
