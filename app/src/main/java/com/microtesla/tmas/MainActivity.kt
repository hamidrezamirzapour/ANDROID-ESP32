package com.microtesla.tmas

import android.content.ContentValues
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnExport: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRecords: TextView

    private lateinit var tvTemp1: TextView
    private lateinit var tvTemp2: TextView
    private lateinit var tvTemp3: TextView

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private var isConnected = false
    private var socket: Socket? = null
    private var readJob: Job? = null

    private var recordCount = 0
    private var pointIndex = 0f
    private val MAX_POINTS = 60
    private val recordedData = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        btnConnect = findViewById(R.id.btnConnect)
        btnExport = findViewById(R.id.btnExport)
        tvStatus = findViewById(R.id.tvStatus)
        tvRecords = findViewById(R.id.tvRecords)

        tvTemp1 = findViewById(R.id.tvTemp1)
        tvTemp2 = findViewById(R.id.tvTemp2)
        tvTemp3 = findViewById(R.id.tvTemp3)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        setupChart(chart1, "#00d2d3")
        setupChart(chart2, "#2ecc71")
        setupChart(chart3, "#e74c3c")

        recordedData.add("Time,Sensor1,Sensor2,Sensor3")

        btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        btnExport.setOnClickListener {
            exportToCSV()
        }
    }

    private fun setupChart(chart: LineChart, colorHex: String) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setBackgroundColor(Color.parseColor("#1a2332"))

        val dataSet = LineDataSet(ArrayList<Entry>(), "Temperature")
        dataSet.color = Color.parseColor(colorHex)
        dataSet.setCircleColor(Color.parseColor(colorHex))
        dataSet.lineWidth = 2f
        dataSet.circleRadius = 3f
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER

        val lineData = LineData(dataSet)
        chart.data = lineData

        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.textColor = Color.WHITE
        chart.xAxis.setDrawGridLines(false)
        chart.axisLeft.textColor = Color.WHITE
        chart.axisLeft.setDrawGridLines(true)
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = false
    }

    private fun addEntryToChart(chart: LineChart, value: Float) {
        val data = chart.data ?: return
        var set = data.getDataSetByIndex(0)
        if (set == null) {
            set = LineDataSet(ArrayList<Entry>(), "Temperature")
            data.addDataSet(set)
        }
        data.addEntry(Entry(pointIndex, value), 0)

        if (set.entryCount > MAX_POINTS) {
            set.removeFirst()
            for (i in 0 until set.entryCount) {
                val entry = set.getEntryForIndex(i)
                entry.x = entry.x - 1f
            }
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun connect() {
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 8888

        updateStatus("● Connecting...", "#f39c12")
        btnConnect.isEnabled = false

        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket()
                socket?.connect(InetSocketAddress(ip, port), 4000)
                isConnected = true

                withContext(Dispatchers.Main) {
                    updateStatus("● Connected", "#2ecc71")
                    btnConnect.text = "Disconnect"
                    btnConnect.isEnabled = true
                }

                val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                while (isConnected) {
                    val line = reader.readLine() ?: break
                    parseData(line)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                disconnectFromThread()
            }
        }
    }

    private fun parseData(data: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                var s1Val = "--.-"
                var s2Val = "--.-"
                var s3Val = "--.-"

                val parts = data.split("|")
                for (part in parts) {
                    val kv = part.split(":")
                    if (kv.size == 2) {
                        val sensor = kv[0].trim()
                        // Clean 'C' from string if present (e.g., 24.5C -> 24.5)
                        val rawVal = kv[1].trim().replace("C", "").trim()
                        val fVal = rawVal.toFloatOrNull()

                        when (sensor) {
                            "S1" -> {
                                tvTemp1.text = "$rawVal °C"
                                if (fVal != null) addEntryToChart(chart1, fVal)
                                s1Val = rawVal
                            }
                            "S2" -> {
                                tvTemp2.text = "$rawVal °C"
                                if (fVal != null) addEntryToChart(chart2, fVal)
                                s2Val = rawVal
                            }
                            "S3" -> {
                                tvTemp3.text = "$rawVal °C"
                                if (fVal != null) addEntryToChart(chart3, fVal)
                                s3Val = rawVal
                            }
                        }
                    }
                }

                val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                recordedData.add("$timeStamp,$s1Val,$s2Val,$s3Val")

                pointIndex++
                recordCount++
                tvRecords.text = "Records: $recordCount"

            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
    }

    private suspend fun disconnectFromThread() {
        isConnected = false
        try {
            socket?.close()
        } catch (e: Exception) {}

        withContext(Dispatchers.Main) {
            updateStatus("● Disconnected", "#e74c3c")
            btnConnect.text = "Connect"
            btnConnect.isEnabled = true
        }
    }

    private fun disconnect() {
        isConnected = false
        readJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                socket?.close()
            } catch (e: Exception) {}
            disconnectFromThread()
        }
    }

    private fun updateStatus(text: String, colorHex: String) {
        tvStatus.text = text
        tvStatus.setTextColor(Color.parseColor(colorHex))
    }

    private fun exportToCSV() {
        if (recordedData.size <= 1) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "TMAS_Log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    recordedData.forEach { line ->
                        outputStream.write("$line\n".toByteArray())
                    }
                }
                Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
