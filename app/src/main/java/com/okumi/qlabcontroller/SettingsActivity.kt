package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialCardView>(R.id.debugCard).setOnClickListener {
            val intent = Intent(this, DebugLogActivity::class.java)
            startActivity(intent)
        }

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }
    }
}
