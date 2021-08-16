package kz.btsdigital.aitu.embedded

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kz.btsdigital.aitu.embedded.internal.KundelikUserInfoPayload

class SampleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AituBridgeSettings.setup {
            isDebug = true

            authTokenProvider = { "fakeAuthToken" }

            userInfoProvider = {
                KundelikUserInfoPayload(
                    userId = "fakeUserId",
                    role = KundelikUserInfoPayload.ROLE_STAFF,
                    classId = null
                )
            }

            showNewMessengerEvent = { event ->
                when (event) {
                    NewMessage -> toast("Got 'New message' event")
                    is UnknownEvent -> {
                        toast("Got unknown event with type = ${event.type}")
                    }
                }
            }
        }

        val aituWebViewFragment = AituWebViewFragment.create(
            url = "https://astanajs.kz/test-kundelik",
            // url = "https://kundelik.aitu.io?theme=light&lang=ru",
        )
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, aituWebViewFragment)
            .commitNowAllowingStateLoss()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
