package com.example.agent

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.data.model.ContactCandidate
import com.example.data.model.ContactNumberOption

class ContactResolver(private val context: Context) {

    sealed class ResolveResult {
        data class SingleMatch(val name: String, val number: String, val label: String) : ResolveResult()
        data class MultipleMatches(val candidates: List<ContactCandidate>) : ResolveResult()
        object PermissionDenied : ResolveResult()
        object NotFound : ResolveResult()
    }

    fun hasContactPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun resolveContact(query: String): ResolveResult {
        if (!hasContactPermission()) {
            return ResolveResult.PermissionDenied
        }

        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return ResolveResult.NotFound

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
        )

        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$cleanQuery%")
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        val candidateMap = mutableMapOf<String, MutableList<ContactNumberOption>>()
        val nameMap = mutableMapOf<String, String>()

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            if (cursor != null && cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                val primaryIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)

                do {
                    val contactId = cursor.getString(idIdx) ?: continue
                    val displayName = cursor.getString(nameIdx) ?: "Unknown"
                    val rawNumber = cursor.getString(numIdx) ?: continue
                    val type = cursor.getInt(typeIdx)
                    val customLabel = cursor.getString(labelIdx) ?: ""
                    val isPrimary = (cursor.getInt(primaryIdx) != 0)

                    val label = when (type) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
                        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> if (customLabel.isNotBlank()) customLabel else "Other"
                        else -> "Other"
                    }

                    val normalized = normalizePhoneNumber(rawNumber)
                    val option = ContactNumberOption(
                        normalizedNumber = normalized,
                        displayNumber = rawNumber.trim(),
                        label = label,
                        isPrimary = isPrimary
                    )

                    nameMap[contactId] = displayName
                    candidateMap.getOrPut(contactId) { mutableListOf() }.add(option)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            return ResolveResult.NotFound
        } finally {
            cursor?.close()
        }

        if (candidateMap.isEmpty()) {
            return ResolveResult.NotFound
        }

        val candidates = candidateMap.map { (id, numbers) ->
            ContactCandidate(
                contactId = id,
                displayName = nameMap[id] ?: "Unknown",
                numbers = numbers.distinctBy { it.normalizedNumber }
            )
        }

        // Exact name match priority
        val exactMatch = candidates.find { it.displayName.equals(cleanQuery, ignoreCase = true) }
        val targetCandidates = if (exactMatch != null && candidates.size > 1) {
            listOf(exactMatch)
        } else {
            candidates
        }

        if (targetCandidates.size == 1) {
            val single = targetCandidates.first()
            if (single.numbers.size == 1) {
                val num = single.numbers.first()
                return ResolveResult.SingleMatch(single.displayName, num.normalizedNumber, num.label)
            }
        }

        return ResolveResult.MultipleMatches(targetCandidates)
    }

    fun selectByDisambiguationRule(
        candidates: List<ContactCandidate>,
        userSelection: String
    ): Pair<ContactCandidate, ContactNumberOption>? {
        val clean = userSelection.trim().lowercase()

        // 1. Ordinal matching: "first", "pehla", "1st", "second", "dusra", "2nd"
        val ordinalIndex = when {
            clean.contains("first") || clean.contains("pehla") || clean.contains("1st") || clean == "1" -> 0
            clean.contains("second") || clean.contains("dusra") || clean.contains("2nd") || clean == "2" -> 1
            clean.contains("third") || clean.contains("teesra") || clean.contains("3rd") || clean == "3" -> 2
            else -> -1
        }

        if (ordinalIndex >= 0) {
            if (candidates.size == 1) {
                val numbers = candidates.first().numbers
                if (ordinalIndex < numbers.size) {
                    return Pair(candidates.first(), numbers[ordinalIndex])
                }
            } else if (ordinalIndex < candidates.size) {
                val cand = candidates[ordinalIndex]
                return Pair(cand, cand.numbers.first())
            }
        }

        // 2. Label matching: "mobile", "work", "home", "personal"
        for (cand in candidates) {
            val matchingNum = cand.numbers.find { num ->
                clean.contains(num.label.lowercase())
            }
            if (matchingNum != null) {
                return Pair(cand, matchingNum)
            }
        }

        // 3. Suffix matching: e.g. "1700 wale", "last 4 digits 1700", "ending in 4321"
        val digitsOnly = clean.filter { it.isDigit() }
        if (digitsOnly.length >= 3) {
            for (cand in candidates) {
                val matchingNum = cand.numbers.find { num ->
                    num.normalizedNumber.endsWith(digitsOnly)
                }
                if (matchingNum != null) {
                    return Pair(cand, matchingNum)
                }
            }
        }

        return null
    }

    private fun normalizePhoneNumber(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' }
    }
}
