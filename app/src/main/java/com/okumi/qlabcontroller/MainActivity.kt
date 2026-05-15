package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupClickListeners()
    }

    private fun setupClickListeners() {
        findViewById<MaterialCardView>(R.id.connectionCard).setOnClickListener {
            val intent = Intent(this, NetworkScanActivity::class.java)
            startActivity(intent)
        }

        findViewById<MaterialCardView>(R.id.settingsCard).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
}
