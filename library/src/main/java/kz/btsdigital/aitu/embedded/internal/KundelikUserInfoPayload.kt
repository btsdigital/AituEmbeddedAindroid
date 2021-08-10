package kz.btsdigital.aitu.embedded.internal

data class KundelikUserInfoPayload(
    val userId: String,
    val role: String,
    val classId: String?,
) {
    companion object{
        const val ROLE_RODITEL = "roditel"
        const val ROLE_UCHITEL = "uchitel"
        const val ROLE_UCHENIK = "uchenik"
    }
}