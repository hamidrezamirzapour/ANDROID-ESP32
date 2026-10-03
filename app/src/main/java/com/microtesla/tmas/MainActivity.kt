package com.microtesla.tmas

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvSensor1: TextView
    private lateinit var tvSensor2: TextView
    private lateinit var tvSensor3: TextView

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private val maxEntries = 30
    private var count1 = 0f
    private var count2 = 0f
    private var count3 = 0f

    private val espIp = "192.168.4.1"
    private val espPort = 8888

    private var isRunning = false
    private var socket: Socket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)

        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvSensor1 = findViewById(R.id.tvSensor1)
        tvSensor2 = findViewById(R.id.tvSensor2)
        tvSensor3 = findViewById(R.id.tvSensor3)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        setupChart(chart1, Color.parseColor("#00D2D3"))
        setupChart(chart2, Color.parseColor("#10AC84"))
        setupChart(chart3, Color.parseColor("#FF6B6B"))

        startTcpClient()
    }

    private fun setupChart(chart: LineChart, color: Int) {
        val dataSet = LineDataSet(mutableListOf(), "").apply {
            this.color = color
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        chart.data = LineData(dataSet)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.xAxis.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            textColor = Color.LTGRAY
            gridColor = Color.DKGRAY
        }
        chart.invalidate()
    }

    private fun addEntryToChart(chart: LineChart, x: Float, y: Float) {
        val data = chart.data ?: return
        val set = data.getDataSetByIndex(0) ?: return

        data.addEntry(Entry(x, y), 0)
        if (set.entryCount > maxEntries) {
            set.removeFirst()
        }
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxEntries.toFloat())
        chart.moveViewToX(x)
    }

    private fun startTcpClient() {
        if (isRunning) return
        isRunning = true

        updateStatus("Connecting...", Color.parseColor("#FFA500"))

        Thread {
            while (isRunning) {
                try {
                    socket = Socket(espIp, espPort)
                    socket?.soTimeout = 5000

                    updateStatus("Connected (TCP)", Color.GREEN)

                    val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                    var line: String?

                    while (isRunning && socket?.isConnected == true) {
                        line = reader.readLine()
                        if (line != null) {
                            parseIncomingData(line)
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TCP_CLIENT", "Error: ${e.message}")
                    updateStatus("Disconnected", Color.RED)
                } finally {
                    try {
                        socket?.close()
                        socket = null
                    } catch (_: Exception) {}
                }

                if (isRunning) {
                    Thread.sleep(3000)
                }
            }
        }.start()
    }

    private fun parseIncomingData(data: String) {
        try {
            var val1: Float? = null
            var val2: Float? = null
            var val3: Float? = null

            val trimmed = data.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                // قالب JSON مانند: {"s1": 25.4, "s2": 26.1, "s3": 24.8}
                val json = JSONObject(trimmed)
                if (json.has("s1")) val1 = json.getDouble("s1").toFloat()
                if (json.has("s2")) val2 = json.getDouble("s2").toFloat()
                if (json.has("s3")) val3 = json.getDouble("s3").toFloat()
            } else {
                // قالب خطی متنی مانند: T1:25.5C|T2:26.0C|T3:24.8C
                val parts = trimmed.split("|")
                for (part in parts) {
                    val p = part.trim()
                    if (p.startsWith("T1:")) {
                        val1 = p.replace("T1:", "").replace("C", "").trim().toFloatOrNull()
                    } else if (p.startsWith("T2:")) {
                        val2 = p.replace("T2:", "").replace("C", "").trim().toFloatOrNull()
                    } else if (p.startsWith("T3:")) {
                        val3 = p.replace("T3:", "").replace("C", "").trim().toFloatOrNull()
                    }
                }
            }

            mainHandler.post {
                val1?.let {
                    tvSensor1.text = String.format("%.2f °C", it)
                    addEntryToChart(chart1, count1++, it)
                }
                val2?.let {
                    tvSensor2.text = String.format("%.2f °C", it)
                    addEntryToChart(chart2, count2++, it)
                }
                val3?.let {
                    tvSensor3.text = String.format("%.2f °C", it)
                    addEntryToChart(chart3, count3++, it)
                }
            }
        } catch (e: Exception) {
            Log.e("DATA_PARSE", "Error parsing: $data", e)
        }
    }

    private fun updateStatus(text: String, color: Int) {
        mainHandler.post {
            tvConnStatus.text = text
            tvConnStatus.setTextColor(color)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
    }
}
