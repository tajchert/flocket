package com.mtajchert.flocket.core.ui.component

import androidx.compose.runtime.Immutable
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.ui.R
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiText

/** What is wrong with the import form itself (as opposed to [ImportKeyDialogState.error], which the repository returned). */
enum class ImportKeyProblem { LabelMissing, NotAPrivateKey }

/**
 * State of the "Import a private key" dialog, shared by Settings, the host form and onboarding. Immutable: every
 * `with...` returns a new state. [pem] holds the picked file's text in [KeySource.File] mode; it is never shown
 * there, only [fileName].
 */
@Immutable
data class ImportKeyDialogState(
    val source: KeySource = KeySource.File,
    val fileName: String? = null,
    val label: String = "",
    val pem: String = "",
    val passphrase: String = "",
    val busy: Boolean = false,
    val problem: ImportKeyProblem? = null,
    val error: UiError? = null,
) {
    // Never print key material.
    override fun toString(): String = "ImportKeyDialogState(source=$source, fileName=$fileName, busy=$busy)"

    /** A key file was picked: keep its text, name the key after the file when unnamed, pre-check it. */
    fun withFilePicked(file: PickedKeyFile): ImportKeyDialogState =
        copy(
            source = KeySource.File,
            fileName = file.name,
            pem = file.text,
            label = label.ifBlank { keyLabelFromFileName(file.name).orEmpty() },
            problem = if (looksLikePrivateKey(file.text)) null else ImportKeyProblem.NotAPrivateKey,
            error = null,
        )

    /** Switching between file and paste drops whatever key text the other mode held. */
    fun withSource(newSource: KeySource): ImportKeyDialogState =
        if (newSource == source) {
            this
        } else {
            copy(source = newSource, fileName = null, pem = "", problem = null, error = null)
        }

    fun withChange(
        label: String? = null,
        pem: String? = null,
        passphrase: String? = null,
    ): ImportKeyDialogState =
        copy(
            label = label ?: this.label,
            pem = pem ?: this.pem,
            passphrase = passphrase ?: this.passphrase,
            problem = null,
            error = null,
        )

    /** The picked file could not be read: say so inside the dialog and keep the other inputs. */
    fun withFileFailed(): ImportKeyDialogState =
        copy(
            error =
                UiError(
                    title = UiText.Res(R.string.key_import_file_failed),
                    message = UiText.Res(R.string.key_import_file_failed_message),
                ),
        )

    /** The first problem that stops an import, or null when it can go ahead. */
    fun validate(): ImportKeyProblem? =
        when {
            label.isBlank() -> ImportKeyProblem.LabelMissing
            !looksLikePrivateKey(pem) -> ImportKeyProblem.NotAPrivateKey
            else -> null
        }
}

/**
 * Imports the key the dialog describes and wipes the key text and passphrase copies it made. The caller has
 * already validated [state].
 */
suspend fun DeviceKeyRepository.importFrom(state: ImportKeyDialogState): AppResult<DeviceKey> {
    val pem = state.pem.trim().toCharArray()
    val passphrase = state.passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
    return try {
        import(state.label.trim(), pem, passphrase)
    } finally {
        pem.fill('\u0000')
        passphrase?.fill('\u0000')
    }
}
