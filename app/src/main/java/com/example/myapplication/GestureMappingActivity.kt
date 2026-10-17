package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray

/** Own mapping screen. Does not request accessibility permission. */
class GestureMappingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).padding(16.dp)) {
                        Text("动作手势映射",
                            style = MaterialTheme.typography.headlineSmall)
                        Text("先做出下方规定的起手式，识别到后会显示悬浮提醒；完成方向动作才执行操作。",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        val names = remember {
                            val customLabels = try {
                                val json = assets.open("gesture_labels.json")
                                    .bufferedReader().use { it.readText() }
                                val a = JSONArray(json)
                                (0 until a.length()).map { a.getString(it) }
                                    .filterNot { it == "None" || it == "No_Gesture" }
                            } catch (_: Exception) { emptyList() }
                            GestureCatalog.known
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(names) { name ->
                                var action by remember(name) {
                                    mutableStateOf(GestureActionMap.actionFor(this@GestureMappingActivity, name))
                                }
                                var expanded by remember { mutableStateOf(false) }
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(GestureCatalog.startingPoses[name] ?: "先摆出起手式，再做方向动作",
                                        style = MaterialTheme.typography.bodySmall)
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(GestureCatalog.label(name),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium)
                                        Box {
                                            TextButton(onClick = { expanded = true }) { Text(GestureActionMap.label(action)) }
                                            DropdownMenu(expanded = expanded,
                                                onDismissRequest = { expanded = false }) {
                                                GestureActionMap.actions.forEach { next ->
                                                    DropdownMenuItem(text = { Text(GestureActionMap.label(next)) },
                                                        onClick = {
                                                            action = next
                                                            GestureActionMap.save(
                                                                this@GestureMappingActivity, name, next)
                                                            expanded = false
                                                        })
                                                }
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}