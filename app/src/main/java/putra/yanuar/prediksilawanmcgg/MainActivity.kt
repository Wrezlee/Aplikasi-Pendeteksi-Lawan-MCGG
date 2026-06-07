package putra.yanuar.prediksilawanmcgg

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import putra.yanuar.prediksilawanmcgg.TrackingService

class MainActivity : AppCompatActivity() {
    private val CODE_DRAW_OVERLAY_PERMISSION = 2084

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val button = Button(this).apply {
            text = "Aktifkan MC GG Tracker"
        }
        setContentView(button)

        button.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                @Suppress("DEPRECATION")
                startActivityForResult(intent, CODE_DRAW_OVERLAY_PERMISSION)
            } else {
                startFloatingWidget()
            }
        }
    }

    private fun startFloatingWidget() {
        startForegroundService(Intent(this, TrackingService::class.java))
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CODE_DRAW_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingWidget()
            } else {
                Toast.makeText(this, "Izin Overlay ditolak. Aplikasi tidak bisa berjalan.", Toast.LENGTH_SHORT).show()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}