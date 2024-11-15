package com.example.autocaptcha.ui.settings

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.autocaptcha.MainActivity
import com.example.autocaptcha.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sharedPreferences =
            requireActivity().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        binding.switchForwardScreenlocked.setOnCheckedChangeListener { _, isChecked ->
            editor.putBoolean("forwardOnlyScreenLocked", isChecked)
            editor.apply()
            Log.d("SettingsFragment", "Switch 状态改变: $isChecked")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
