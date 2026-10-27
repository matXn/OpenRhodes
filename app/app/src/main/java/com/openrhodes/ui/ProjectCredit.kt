package com.openrhodes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.R

@Composable
internal fun ProjectCredit() {
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = "GitHub 项目 OpenRhodes" }
            .clickable(role = Role.Button) {
                uri.openUri("https://github.com/matXn/OpenRhodes")
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("By", color = Muted, fontSize = 12.sp)
        Icon(
            painterResource(R.drawable.github_mark),
            null,
            tint = Muted,
            modifier = Modifier.size(14.dp),
        )
        Text("OpenRhodes", color = Muted, fontSize = 12.sp)
    }
}
