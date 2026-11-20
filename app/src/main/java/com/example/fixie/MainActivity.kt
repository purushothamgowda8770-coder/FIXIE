
package com.example.fixie

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

private const val API_URL = "http://192.168.3.61:8000/scan"
private const val TAG = "FIXIE"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AutoPatchApp()
        }
    }
}

@Composable
fun AutoPatchApp() {

    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var fileName by remember { mutableStateOf("") }
    var fileContent by remember { mutableStateOf("") }
    var message by remember {
        mutableStateOf("Ready to analyse your code.")
    }

    var scanResult by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->

        if (uri != null) {
            try {
                val name = context.contentResolver
                    .query(uri, null, null, null, null)
                    ?.use { cursor ->
                        val index = cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )

                        if (index >= 0 && cursor.moveToFirst()) {
                            cursor.getString(index)
                        } else {
                            "Selected file"
                        }
                    } ?: "Selected file"

                if (!name.endsWith(".py", ignoreCase = true)) {
                    throw Exception("Please select a Python .py file.")
                }

                val content = context.contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: throw Exception("Unable to read selected file.")

                if (content.toByteArray().size > 1024 * 1024) {
                    throw Exception("File must be smaller than 1 MB.")
                }

                fileName = name
                fileContent = content
                scanResult = ""
                message = "File ready for bug analysis."

            } catch (e: Exception) {
                fileName = ""
                fileContent = ""
                message = "Upload failed: ${e.message}"
            }
        }
    }

    MaterialTheme {

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF4F6FA)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                Text(
                    "🛠 AutoPatch",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2457D6)
                )

                Text(
                    "Welcome to smarter debugging.",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Detect bugs. Understand causes. Suggest fixes."
                )

                Button(
                    onClick = {
                        filePicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📁 Upload Your Code")
                }

                OutlinedButton(
                    onClick = {

                        if (fileContent.isBlank()) {
                            message = "Please upload a Python file first."
                            return@OutlinedButton
                        }

                        if (isScanning) {
                            return@OutlinedButton
                        }

                        isScanning = true
                        scanResult = ""
                        message = "Connecting to FIXIE backend..."

                        executor.execute {

                            var connection: HttpURLConnection? = null

                            try {

                                Log.i(TAG, "Starting scan request")
                                Log.i(TAG, "API address: $API_URL")

                                connection = URL(API_URL)
                                    .openConnection() as HttpURLConnection

                                connection.requestMethod = "POST"
                                connection.connectTimeout = 20000
                                connection.readTimeout = 30000
                                connection.doOutput = true

                                connection.setRequestProperty(
                                    "Content-Type",
                                    "application/json; charset=UTF-8"
                                )

                                connection.setRequestProperty(
                                    "Accept",
                                    "application/json"
                                )

                                val request = JSONObject()
                                    .put("code", fileContent)
                                    .toString()

                                OutputStreamWriter(
                                    connection.outputStream,
                                    Charsets.UTF_8
                                ).use { writer ->
                                    writer.write(request)
                                    writer.flush()
                                }

                                val responseCode = connection.responseCode

                                Log.i(TAG, "HTTP response: $responseCode")

                                val responseStream =
                                    if (responseCode in 200..299) {
                                        connection.inputStream
                                    } else {
                                        connection.errorStream
                                    }

                                val response = responseStream
                                    ?.bufferedReader()
                                    ?.use { it.readText() }
                                    ?: throw Exception(
                                        "The server returned an empty response."
                                    )

                                Log.i(TAG, "Response received: $response")

                                if (responseCode !in 200..299) {
                                    throw Exception(
                                        "Server returned HTTP $responseCode: $response"
                                    )
                                }

                                val json = JSONObject(response)

                                val totalBugs = json.optInt("total_bugs", 0)
                                val results = json.optJSONArray("results")
                                    ?: throw Exception(
                                        "Server response does not contain results."
                                    )

                                val output = StringBuilder()

                                output.append("SCAN COMPLETE\n\n")
                                output.append("File: $fileName\n")
                                output.append("Total issues: $totalBugs\n\n")

                                if (totalBugs == 0) {

                                    output.append(
                                        "No bugs detected by the current scanner.\n"
                                    )

                                } else {

                                    for (i in 0 until results.length()) {

                                        val bug = results.getJSONObject(i)

                                        output.append(
                                            "ISSUE ${i + 1}\n"
                                        )

                                        output.append(
                                            "Type: ${bug.optString("type")}\n"
                                        )

                                        output.append(
                                            "Severity: ${bug.optString("severity")}\n"
                                        )

                                        val line = bug.opt("line")

                                        output.append(
                                            "Line: ${
                                                if (line == null ||
                                                    line == JSONObject.NULL
                                                ) {
                                                    "Not specified"
                                                } else {
                                                    line.toString()
                                                }
                                            }\n"
                                        )

                                        output.append(
                                            "Problem: ${bug.optString("message")}\n"
                                        )

                                        output.append(
                                            "Root cause: ${bug.optString("root_cause")}\n"
                                        )

                                        output.append(
                                            "Suggested fix: ${bug.optString("suggestion")}\n\n"
                                        )
                                    }
                                }

                                mainHandler.post {
                                    scanResult = output.toString()
                                    message = "Bug analysis completed successfully!"
                                    isScanning = false
                                }

                            } catch (e: Exception) {

                                Log.e(TAG, "Scan failed", e)

                                mainHandler.post {
                                    message =
                                        "Scan failed: ${e.javaClass.simpleName}: ${e.message}"

                                    scanResult = ""
                                    isScanning = false
                                }

                            } finally {
                                connection?.disconnect()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isScanning
                ) {

                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )

                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning...")
                    } else {
                        Text("🔍 Scan for Bugs")
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "System Status",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(message)
                    }
                }

                if (fileName.isNotBlank()) {

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                "Uploaded File",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )

                            Text(fileName)

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "Source Code Preview",
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = fileContent.take(5000),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (scanResult.isNotBlank()) {

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                "FIXIE Bug Analysis Report",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = Color(0xFF2457D6)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = scanResult,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Text(
                    "Recent Activity",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Text("Your uploaded files will appear here.")
            }
        }
    }
}
