package com.trailrelay.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

enum class TopDestination { MY_TRAILS, COMMUNITY, OFFLINE, SETTINGS }

/** Map's single application-navigation surface. Secondary screens use normal Back. */
class ExploreSheet : BottomSheetDialogFragment() {
    override fun getTheme() = R.style.ThemeOverlay_TrailRelay_BottomSheet
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.sheet_explore, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        fun row(id: Int, destination: TopDestination?) {
            view.findViewById<View>(id).setOnClickListener {
                dismiss()
                (requireActivity() as MainActivity).openTopDestination(destination)
            }
        }
        row(R.id.explore_my_trails, TopDestination.MY_TRAILS)
        row(R.id.explore_community, TopDestination.COMMUNITY)
        row(R.id.explore_offline, TopDestination.OFFLINE)
        row(R.id.explore_import, null)
        view.findViewById<View>(R.id.explore_settings).setOnClickListener {
            dismiss()
            (requireActivity() as MainActivity).openTopDestination(TopDestination.SETTINGS)
        }
    }
}
