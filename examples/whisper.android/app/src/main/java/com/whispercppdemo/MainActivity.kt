package com.whispercppdemo

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.whispercppdemo.ui.main.MainScreen
import com.whispercppdemo.ui.main.MainScreenViewModel
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import android.Manifest
import java.nio.file.Files.createFile

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }


        setContent {
            WhisperCppDemoTheme {
                MainScreen(viewModel)
            }
        }


    }
}