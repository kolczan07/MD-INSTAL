package pl.mdinstal.narzedzia

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

class ActionProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MDINSTAL", "ActionProxyActivity onCreate action=${intent?.action}")
        
        val action = intent?.action
        if (action != null) {
            val serviceIntent = Intent(this, RadioService::class.java).apply {
                this.action = action
                putExtras(intent)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        finish()
    }
}
