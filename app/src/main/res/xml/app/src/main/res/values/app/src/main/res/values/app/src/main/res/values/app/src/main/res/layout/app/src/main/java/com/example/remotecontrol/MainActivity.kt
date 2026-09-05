package com.example.remotecontrol

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.remotecontrol.databinding.ActivityMainBinding
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var roomCode: String

    // ⚠️ Remplace par l'URL wss:// de TON serveur Render (pas https://)
    private val serverUrl = "wss://signaling-server-2mly.onrender.com"

    private val screenCaptureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("data", result.data)
                    putExtra("roomCode", roomCode)
                    putExtra("serverUrl", serverUrl)
                }
                ContextCompatStartForegroundService(this, serviceIntent)
                Toast.makeText(this, "Partage d'écran démarré", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Code à 6 chiffres généré une fois — c'est celui qu'on colle
        // dans controller.html?code=XXXXXX sur l'iPhone
        roomCode = (100000..999999).random(Random(System.currentTimeMillis())).toString()
        binding.codeText.text = roomCode
        binding.linkText.text = "signaling-server-2mly.onrender.com/controller.html?code=$roomCode"

        projectionManager = getSystemService(MediaProjectionManager::class.java)

        binding.startButton.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Active d'abord le service d'accessibilité", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.shareButton.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://${binding.linkText.text}")
            }
            startActivity(Intent.createChooser(shareIntent, "Envoyer le lien"))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${RemoteAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponentName, ignoreCase = true)) return true
        }
        return false
    }
}

private fun ContextCompatStartForegroundService(activity: Activity, intent: Intent) {
    if (Build.VERSION.SDK_INT >= 26) {
        activity.startForegroundService(intent)
    } else {
        activity.startService(intent)
    }
}
