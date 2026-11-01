package com.mtajchert.flocket.feature.hosts.impl.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.ErrorBanner
import com.mtajchert.flocket.core.ui.component.LoadingState
import com.mtajchert.flocket.core.ui.hostform.HostForm
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import com.mtajchert.flocket.feature.hosts.impl.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    state: HostEditUiState,
    onFormChange: (HostFormState) -> Unit,
    onCreateKey: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (state.isNew) R.string.edit_title_add else R.string.edit_title_edit),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                    ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.edit_close)) }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = !state.saving && !state.loading,
                    ) { Text(stringResource(R.string.edit_save)) }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
        } else {
            CenteredContent(Modifier.padding(padding).imePadding()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    state.error?.let { ErrorBanner(it, Modifier.padding(bottom = 12.dp)) }
                    HostForm(
                        state = state.form,
                        errors = state.errors,
                        keys = state.keys,
                        onChange = onFormChange,
                        onCreateKey = onCreateKey,
                        isCreatingKey = state.creatingKey,
                    )
                }
            }
        }
    }
}
