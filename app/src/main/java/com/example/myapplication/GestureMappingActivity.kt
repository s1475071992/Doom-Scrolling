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
                        Text("现有八种动态手势：✋ 张掌→握拳默认单击；✊ 握拳→张掌默认双击；其余食指和双指移动操作不变。先稳定起手式约半秒，再改变手形或移动；静止不触发。默认点击屏幕中心，动作可自定义。",
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