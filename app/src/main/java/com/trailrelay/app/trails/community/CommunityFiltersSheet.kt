package com.trailrelay.app.trails.community

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.trailrelay.app.R

class CommunityFiltersSheet : BottomSheetDialogFragment() {
    override fun getTheme() = R.style.ThemeOverlay_TrailRelay_BottomSheet
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.sheet_community_filters, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model = ViewModelProvider(requireActivity())[CommunityModel::class.java]
        fun filter(id: Int, label: Int, values: List<String>, selected: String?) {
            val options = listOf(getString(label)) + (values + listOfNotNull(selected)).distinct().sortedBy { it.lowercase() }
            view.findViewById<Spinner>(id).apply {
                adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(selected?.let(options::indexOf)?.coerceAtLeast(0) ?: 0)
            }
        }
        filter(R.id.state_filter, R.string.all_states, model.entries.mapNotNull { it.state }, model.state)
        filter(R.id.difficulty_filter, R.string.all_difficulties, model.entries.mapNotNull { it.difficulty }, model.difficulty)
        filter(R.id.vehicle_filter, R.string.all_vehicles,
            VEHICLE_TYPES + model.entries.flatMap { it.vehicleTypes.orEmpty() }, model.vehicle)
        view.findViewById<Button>(R.id.filters_clear).setOnClickListener {
            listOf(R.id.state_filter, R.id.difficulty_filter, R.id.vehicle_filter).forEach {
                view.findViewById<Spinner>(it).setSelection(0)
            }
        }
        view.findViewById<Button>(R.id.filters_apply).setOnClickListener {
            fun selected(id: Int): String? = view.findViewById<Spinner>(id).let {
                if (it.selectedItemPosition == 0) null else it.selectedItem as String
            }
            model.state = selected(R.id.state_filter)
            model.difficulty = selected(R.id.difficulty_filter)
            model.vehicle = selected(R.id.vehicle_filter)
            parentFragmentManager.setFragmentResult(RESULT, Bundle())
            dismiss()
        }
    }

    companion object { const val RESULT = "communityFiltersChanged" }
}
