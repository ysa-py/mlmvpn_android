package com.mlmvpn.scanner.ui.tunnel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.V2RayOuterLeg
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.ui.settings.IosOption
import com.mlmvpn.scanner.ui.settings.IosPickerScreen
import com.mlmvpn.scanner.utils.S
import com.mlmvpn.scanner.R

@Composable
fun V2RayNodePickerPage(
    selectedId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    backLabel: String,
) {
    val context = LocalContext.current
    val ownPorts = remember { V2RayOuterLeg.ownPorts(context) }
    val nodes = remember { NodeManager(context).nodes.toList() }
    val eligible = remember(nodes) { nodes.filter { V2RayOuterLeg.isEligible(it, ownPorts) } }

    val options = buildList {
        add(IosOption("auto", S(R.string.best_working_node), S(R.string.best_working_node_hint)))
        eligible.forEach { n ->
            add(IosOption(n.id, n.name, "${n.type} · ${V2RayOuterLeg.maskedHostPort("", 0).let { "" }}${n.uri.take(40)}"))
        }
    }

    IosPickerScreen(
        title = S(R.string.v2ray_node),
        options = options,
        selectedKey = selectedId,
        onSelect = onSelect,
        onBack = onBack,
        backLabel = backLabel,
        footer = if (eligible.isEmpty()) {
            S(R.string.v2ray_no_nodes_hint)
        } else {
            S(R.string.v2ray_privacy_footer)
        },
    )
}
