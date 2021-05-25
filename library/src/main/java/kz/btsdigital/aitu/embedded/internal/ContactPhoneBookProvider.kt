package kz.btsdigital.aitu.embedded.internal

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName

internal class ContactPhoneBookProvider(private val context: Context) {

    private val nonDigitsRegex = Regex("[^\\d]")
    private val cachedContacts = mutableListOf<PhoneContact>()

    fun getContacts(): List<PhoneContact> {
        if (cachedContacts.isEmpty()) {
            val contactsMap = mutableMapOf<String, MutableList<PhoneContact>>()

            val projectionNames = arrayOf(
                StructuredName.LOOKUP_KEY,
                Phone.NUMBER,
                StructuredName.GIVEN_NAME,
                StructuredName.FAMILY_NAME,
                ContactsContract.Data.DISPLAY_NAME,
                StructuredName.MIDDLE_NAME
            )

            // get all phones
            context.contentResolver.query(Phone.CONTENT_URI, projectionNames, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return emptyList<PhoneContact>()
                val phoneSet = hashSetOf<String>()
                do {
                    // fix java.lang.IllegalStateException: rawNumber must not be null
                    val rawNumber = cursor.getString(cursor.getColumnIndex(Phone.NUMBER)).orEmpty()
                    val number = rawNumber.replace(nonDigitsRegex, "")

                    if (number.isEmpty()) continue
                    if (phoneSet.contains(number)) continue

                    val lookupKey = cursor.getString(cursor.getColumnIndex(Phone.LOOKUP_KEY))
                    val firstName = cursor.getString(cursor.getColumnIndex(StructuredName.GIVEN_NAME))
                    var lastName = cursor.getString(cursor.getColumnIndex(StructuredName.FAMILY_NAME))
                    val middleName = cursor.getString(cursor.getColumnIndex(StructuredName.MIDDLE_NAME))
                    val displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME))

                    var contact = contactsMap[lookupKey]
                    if (contact == null) {
                        contact = mutableListOf()
                        contactsMap[lookupKey] = contact
                    }

                    var contactFirstName = displayName
                    val isNotValidNameString =
                        contactFirstName.isEmpty() || contactFirstName.count { it in '0'..'9' } > 3
                    if (isNotValidNameString) {
                        if (contactFirstName.isEmpty() && !firstName.isNullOrEmpty()) {
                            contactFirstName = firstName.trim()
                        }

                        if (!middleName.isNullOrEmpty()) {
                            if (contactFirstName.isNotEmpty()) {
                                contactFirstName += " ${middleName.trim()}"
                            } else {
                                contactFirstName = middleName.trim()
                            }
                        }
                    } else {
                        val spaceIndex = displayName.lastIndexOf(' ')
                        if (spaceIndex != -1) {
                            contactFirstName = displayName.substring(0, spaceIndex).trim()
                            lastName = displayName.substring(spaceIndex + 1).trim()
                        }
                    }

                    val phone = when {
                        number.startsWith("8") -> number.replaceFirst("8", "7", false)
                        number.startsWith("+7") -> number.replaceFirst("+7", "7", false)
                        else -> number
                    }
                    val phoneContact = PhoneContact(phone, contactFirstName, lastName.orEmpty())
                    contact.add(phoneContact)

                    phoneSet.add(number)
                } while (cursor.moveToNext())
            }

            cachedContacts.clear()
            cachedContacts.addAll(contactsMap.values.flatten())
        }
        return cachedContacts
    }
}
