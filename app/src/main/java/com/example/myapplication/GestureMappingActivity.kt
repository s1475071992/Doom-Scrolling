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
                        Text("只有六项普通手势：握拳伸食指上翻、伸直食指弯下翻、双指伸直后上下左右移动。起手式需保持约半秒，确认后再动作。空鼠捏合不受影响。",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        val names = GestureCatalog.known
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