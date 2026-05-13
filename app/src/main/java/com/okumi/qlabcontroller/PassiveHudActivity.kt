package com.okumi.qlabcontroller

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class PassiveHudActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passive_hud)

        val state = MockShowStateRepository(this).loadState()
        ShowStateBinder(this).bind(state)

        // Deliberately no show-control listeners in this Activity.
    }
}
