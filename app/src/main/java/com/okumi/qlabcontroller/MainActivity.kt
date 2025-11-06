package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import android.widget.Button

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupClickListeners()
    }

    private fun setupClickListeners() {
        findViewById<MaterialCardView>(R.id.scanCard).setOnClickListener {
            // Navigate to network scan screen
            val intent = Intent(this, NetworkScanActivity::class.java)
            startActivity(intent)
        }

        findViewById<MaterialCardView>(R.id.manualCard).setOnClickListener {
            // Navigate to manual connection screen
            val intent = Intent(this, ConnectionActivity::class.java)
            startActivity(intent)
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            // Navigate to settings screen
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
}
