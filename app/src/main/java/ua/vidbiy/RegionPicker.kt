package ua.vidbiy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

// Звичайне порівняння рядків ставить «і», «ї», «є» не на свої місця в абетці.
private val ukrainianOrder: Comparator<String> = Collator.getInstance(Locale.forLanguageTag("uk")).let { c -> Comparator { a, b -> c.compare(a, b) } }

private data class PickerItem(
    val region: Region,
    val subtitle: String?,
    val openId: String?,
)

@Composable
fun RegionPicker(selected: Region, onPick: (Region) -> Unit, modifier: Modifier = Modifier) {
    var tree by remember { mutableStateOf(RegionTreeRepo.cached()) }
    var loading by remember { mutableStateOf(tree == null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var stack by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(reload) {
        if (tree == null) {
            loading = true
            tree = withContext(Dispatchers.IO) { RegionTreeRepo.get() }
            loading = false
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    fun regionFor(t: RegionTree, id: String): Region =
        Regions.bySirenId(id) ?: Region(
            uid = null,
            name = t[id]?.name.orEmpty(),
            sirenId = id,
            ubillingName = null,
            detail = t.path(id).ifEmpty { null },
        )

    val t = tree
    val trimmed = query.trim()
    val items: List<PickerItem> = when {
        trimmed.length >= 2 -> {
            val q = trimmed.lowercase()
            val fromTree = t?.all.orEmpty()
                .filter { it.id != "0" && it.name.lowercase().contains(q) }
                .sortedWith(compareBy<Place> { t!!.ancestors(it.id).size }.thenBy(ukrainianOrder) { it.name })
                .map { PickerItem(regionFor(t!!, it.id), t.path(it.id).ifEmpty { null }, null) }
            val staticOnly = Regions.all
                .filter { it.sirenId == null && it.name.lowercase().contains(q) }
                .map { PickerItem(it, null, null) }
            (staticOnly + fromTree).take(150)
        }

        stack.isEmpty() -> Regions.all.map { r ->
            val hasChildren = r.sirenId != null && t?.get(r.sirenId)?.childIds?.isNotEmpty() == true
            PickerItem(r, null, if (hasChildren) r.sirenId else null)
        }

        else -> {
            val current = stack.last()
            val place = t?.get(current)
            val whole = if (stack.size == 1) "Уся область" else "Увесь район"
            listOf(PickerItem(regionFor(t!!, current), whole, null)) +
                    place?.childIds.orEmpty()
                        .mapNotNull { t[it] }
                        .sortedWith(compareBy(ukrainianOrder) { it.name })
                        .map { PickerItem(regionFor(t, it.id), null, it.id.takeIf { _ -> it.childIds.isNotEmpty() }) }
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Пошук: місто, громада, район") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Night.TextDim) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Очистити", tint = Night.TextDim)
                    }
                }
            },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Night.GlassBorder,
                focusedBorderColor = Night.Amber,
                unfocusedContainerColor = Night.Glass,
                focusedContainerColor = Night.Glass,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (trimmed.length < 2 && stack.isNotEmpty() && t != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { stack = stack.dropLast(1) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Назад", tint = Night.Amber)
                Spacer(Modifier.width(4.dp))
                Text(
                    stack.mapNotNull { t[it]?.name }.joinToString(" › "),
                    style = MaterialTheme.typography.labelLarge,
                    color = Night.Amber,
                )
            }
        }

        if (loading && trimmed.length >= 2) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = Night.Amber)
                Spacer(Modifier.width(12.dp))
                Text("Завантаження списку громад…", color = Night.TextDim)
            }
        }
        if (!loading && t == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    "Не вдалося завантажити райони й громади. Області доступні.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Night.TextDim,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { reload++ }) { Text("Повторити") }
            }
        }
        if (trimmed.length >= 2 && !loading && items.isEmpty()) {
            Text("Нічого не знайдено", color = Night.TextDim, modifier = Modifier.padding(16.dp))
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            items(items, key = { (it.region.sirenId ?: "uid${it.region.uid}") + (it.subtitle ?: "") }) { item ->
                val isSelected = item.region.sirenId == selected.sirenId && item.region.uid == selected.uid
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            if (item.openId != null) {
                                stack = stack + item.openId
                            } else {
                                onPick(item.region)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.region.name, style = MaterialTheme.typography.bodyLarge)
                        if (item.subtitle != null) {
                            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
                        }
                    }
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = "Обрано", tint = Night.Amber)
                    }
                    if (item.openId != null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Night.TextDim
                        )
                    }
                }
            }
        }
    }
}
