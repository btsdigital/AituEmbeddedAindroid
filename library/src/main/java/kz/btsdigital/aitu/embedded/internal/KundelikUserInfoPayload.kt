package kz.btsdigital.aitu.embedded.internal

data class KundelikUserInfoPayload(
    val userId: String,
    val role: String,
    val classId: String?,
) {
    companion object{
        const val ROLE_PARENT = "EduParent"
        const val ROLE_STAFF = "EduStaff"
        const val ROLE_STUDENT = "EduStudent"
    }
}