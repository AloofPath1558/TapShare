package app.tapshare

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            startActivity(Intent(this, SendActivity::class.java))
        }
        findViewById<Button>(R.id.btnReceive).setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
    }
}
