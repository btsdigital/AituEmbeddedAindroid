package kz.btsdigital.aitu.embedded

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SampleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val aituWebViewFragment = AituWebViewFragment.create(
            url = "https://kundelik.aitu.io?theme=light&lang=ru",
            authTokenProvider = { "fakeAuthToken" },
            isDebug = true
        )
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, aituWebViewFragment)
            .commitNowAllowingStateLoss()
    }
}
