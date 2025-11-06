package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var connectionManager: ConnectionManager
    private lateinit var adapter: SavedConnectionAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateLayout: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionManager = ConnectionManager(this)

        initializeViews()
        setupRecyclerView()
        loadConnections()
    }

    override fun onResume() {
        super.onResume()
        // Reload connections when returning to this activity
        loadConnections()
    }

    private fun initializeViews() {
        recyclerView = findViewById(R.id.connectionsRecyclerView)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)

        findViewById<ImageButton>(R.id.scanButton).setOnClickListener {
            // Navigate to network scan screen
            val intent = Intent(this, NetworkScanActivity::class.java)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.manualButton).setOnClickListener {
            // Navigate to manual connection screen
            val intent = Intent(this, ConnectionActivity::class.java)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            // Navigate to settings screen
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupRecyclerView() {
        adapter = SavedConnectionAdapter(
            connections = mutableListOf(),
            onConnectionClick = { connection ->
                connectToSavedConnection(connection)
            },
            onDeleteClick = { connection ->
                showDeleteDialog(connection)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadConnections() {
        val connections = connectionManager.getSavedConnections()

        if (connections.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateLayout.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateLayout.visibility = View.GONE
            adapter.updateConnections(connections)
        }
    }

    private fun connectToSavedConnection(connection: SavedConnection) {
        // Show passcode dialog if needed
        if (connection.passcode.isEmpty()) {
            showPasscodeDialog(connection)
        } else {
            proceedWithConnection(connection)
        }
    }

    private fun showPasscodeDialog(connection: SavedConnection) {
        val input = android.widget.EditText(this)
        input.hint = "Passcode (optional)"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Connect to ${connection.workspaceName}")
            .setMessage("Enter passcode (leave empty if none)")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val passcode = input.text.toString()
                val updatedConnection = connection.copy(passcode = passcode)
                proceedWithConnection(updatedConnection)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun proceedWithConnection(connection: SavedConnection) {
        lifecycleScope.launch {
            try {
                val qLabManager = QLabOscManager.getInstance()

                // Navigate to connection activity with pre-filled data
                val intent = Intent(this@MainActivity, ConnectionActivity::class.java)
                intent.putExtra("IP_ADDRESS", connection.ipAddress)
                intent.putExtra("PORT", connection.port)
                intent.putExtra("PASSCODE", connection.passcode)
                intent.putExtra("AUTO_CONNECT", true)
                startActivity(intent)

            } catch (e: Exception) {
                LogManager.e("MainActivity", "Failed to connect: ${e.message}")
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection Failed")
                        .setMessage("Failed to connect: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showDeleteDialog(connection: SavedConnection) {
        AlertDialog.Builder(this)
            .setTitle("Delete Connection")
            .setMessage("Remove '${connection.workspaceName}' from recent connections?")
            .setPositiveButton("Delete") { _, _ ->
                connectionManager.deleteConnection(connection.id)
                loadConnections()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
