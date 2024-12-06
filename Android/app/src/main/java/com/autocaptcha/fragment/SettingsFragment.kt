package com.autocaptcha.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.autocaptcha.databinding.FragmentSettingsBinding
import com.autocaptcha.viewmodel.SettingsViewModel

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

        val viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        // 观察开关变动
        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            binding.switchForwardScreenlocked.isChecked =
                settings["forwardOnlyScreenLocked"] ?: true
        }

        binding.switchForwardScreenlocked.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateSetting("forwardOnlyScreenLocked", isChecked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
