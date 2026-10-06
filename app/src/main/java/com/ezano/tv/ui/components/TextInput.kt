package com.ezano.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType

/**
 * TV-friendly text field. D-pad focus lands on a styled surface; pressing OK opens the
 * on-screen keyboard. Pressing Done/Back closes it and returns D-pad navigation.
 */
@Composable
fun EzTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    leadingIcon: ImageVector? = null,
    onSubmit: (() -> Unit)? = null,
    autoEdit: Boolean = false,
) {
    var editing by remember { mutableStateOf(autoEdit) }
    var refocusSurface by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        } else if (refocusSurface) {
            // The surface is only composed again after `editing` flips, so focus it here
            // (requesting it synchronously in finish() would hit a detached FocusRequester).
            refocusSurface = false
            runCatching { surfaceFocus.requestFocus() }
        }
    }

    fun finish(submit: Boolean) {
        keyboard?.hide()
        refocusSurface = true
        editing = false
        if (submit) onSubmit?.invoke()
    }

    Column(modifier) {
        if (label.isNotBlank()) {
            Text(label, style = EzType.Label)
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            if (!editing) {
                FocusSurface(
                    onClick = { editing = true },
                    modifier = Modifier.fillMaxSize().focusRequester(surfaceFocus),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.02f,
                    color = Color.White.copy(alpha = 0.06f),
                    focusedColor = Color.White.copy(alpha = 0.14f),
                    glow = false,
                ) { _ ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (leadingIcon != null) {
                            Icon(leadingIcon, null, tint = EzColors.TextTertiary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        val shown = when {
                            value.isEmpty() -> placeholder
                            password -> "•".repeat(value.length.coerceAtMost(24))
                            else -> value
                        }
                        Text(
                            shown,
                            style = EzType.Title.copy(color = if (value.isEmpty()) EzColors.TextTertiary else EzColors.TextPrimary),
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxSize()
                        .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                        .border(2.dp, EzColors.Accent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, null, tint = EzColors.TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = EzType.Title,
                        cursorBrush = SolidColor(EzColors.Accent2),
                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(
                            onDone = { finish(true) }, onGo = { finish(true) }, onSearch = { finish(true) }, onNext = { finish(true) },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFocus)
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyUp && (e.key == Key.Back || e.key == Key.Escape)) { finish(false); true }
                                else if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp)) { finish(false); true }
                                else false
                            },
                    )
                }
            }
        }
    }
}
