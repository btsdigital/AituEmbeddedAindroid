package kz.btsdigital.aitu.embedded

import kz.btsdigital.aitu.embedded.internal.KundelikUserInfoPayload

object AituBridgeSettings {
    internal var settings = AituBridgeSettingsPayload()

    /**
     * Настройка бриджа
     *
     * Пример:
     * ```
     * AituBridgeSettings.setup {
     *     isDebug = true
     *     authTokenProvider = { "fakeAuthToken" }
     *     userInfoProvider = {
     *         KundelikUserInfoPayload(
     *             userId = "fakeUserId",
     *             role = KundelikUserInfoPayload.ROLE_UCHITEL,
     *             classId = null
     *         )
     *     }
     *
     *     showNewMessengerEvent = { event ->
     *         when (event) {
     *             NewMessage -> toast("Got 'New message' event")
     *             is UnknownEvent -> toast("Got unknown event with type = ${event.type}")
     *         }
     *     }
     * }
     * ```
     */
    fun setup(block: AituBridgeSettingsPayload.() -> Unit) {
        settings.block()
    }
}

class AituBridgeSettingsPayload(
    var authTokenProvider: (() -> String)? = null,
    var userInfoProvider: (() -> KundelikUserInfoPayload)? = null,
    var showNewMessengerEvent: ((AituEvent) -> Unit)? = null,
    var isDebug: Boolean = false
)