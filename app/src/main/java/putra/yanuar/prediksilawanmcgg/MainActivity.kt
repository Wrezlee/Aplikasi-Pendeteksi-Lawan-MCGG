package putra.yanuar.prediksilawanmcgg

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val CODE_DRAW_OVERLAY_PERMISSION = 2084
    private val CODE_MEDIA_PROJECTION = 2085
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val button = Button(this).apply {
            text = "Aktifkan MC GG Tracker"
        }
        setContentView(button)

        button.setOnClickListener { checkOverlayPermission() }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                CODE_DRAW_OVERLAY_PERMISSION
            )
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        // Dialog "Mulai merekam layar?" akan muncul ke user
        @Suppress("DEPRECATION")
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            CODE_MEDIA_PROJECTION
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            CODE_DRAW_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    Toast.makeText(this, "Izin Overlay ditolak. Aplikasi tidak bisa berjalan.", Toast.LENGTH_SHORT).show()
                }
            }
            CODE_MEDIA_PROJECTION -> {
                val intent = Intent(this, TrackingService::class.java)
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Kirim token screen capture ke service
                    intent.putExtra("result_code", resultCode)
                    intent.putExtra("result_data", data)
                } else {
                    // User tolak screen capture → tetap jalankan, tapi mode manual
                    Toast.makeText(
                        this,
                        "Screen capture ditolak — mode manual aktif",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                startForegroundService(intent)
                finish()
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }
}