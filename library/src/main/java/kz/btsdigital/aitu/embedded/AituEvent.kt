package kz.btsdigital.aitu.embedded

sealed class AituEvent

object NewMessage : AituEvent()
data class UnknownEvent(val type: String) : AituEvent()