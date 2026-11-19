package com.voxlink.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.voxlink.ai.ui.VoxApp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startSession() else vm.toast = "Permita o acesso ao microfone para usar o intérprete."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoxApp(vm, onMic = {
                if (vm.sessionActive) vm.stopSession() else requestAndStart()
            })
        }
    }

    private fun requestAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startSession()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Privacy: never keep the microphone open in background.
        if (!isChangingConfigurations) vm.onBackground()
    }
}
