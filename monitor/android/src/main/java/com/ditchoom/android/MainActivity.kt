package com.ditchoom.android

import com.ditchoom.common.App
import android.os.Bundle
import android.os.Debug
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.MaterialTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App(applicationContext)
            }
        }
    }
}