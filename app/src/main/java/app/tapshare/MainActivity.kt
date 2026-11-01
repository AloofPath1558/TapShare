package app.tapshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.rowSend).setOnClickListener {
            startActivity(Intent(this, SendActivity::class.java))
        }
        findViewById<View>(R.id.rowReceive).setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
        findViewById<View>(R.id.rowWeb).setOnClickListener {
            startActivity(Intent(this, WebActivity::class.java))
        }
    }
}
