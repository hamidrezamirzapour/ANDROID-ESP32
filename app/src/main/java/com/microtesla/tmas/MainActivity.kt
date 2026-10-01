package com.microtesla.tmas

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvSensor1: TextView
    private lateinit var tvSensor2: TextView
    private lateinit var tvSensor3: TextView
    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private val maxEntries = 30
    private var chartIndex1 = 0f
    private var chartIndex2 = 0f
    private var chartIndex3 = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    // تنظیمات پیش‌فرض TCP برای ESP32
    private var espIp = "192.168.4.1"
    private var espPort = 8888
    
    private var tcpSocket: Socket? = null
    private var isConnected = false
    private var connectionThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // متصل کردن عناصر رابط کاربری
        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvSensor1 = findViewById(R.id.tvSensor1)
        tvSensor2 = findViewById(R.id.tvSensor2)
        tvSensor3 = findViewById(R.id.tvSensor3)
        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        // بارگذاری تنظیمات ذخیره شده
        loadSettings()

        // راه‌اندازی نمودارها
        setupChart(chart1, Color.parseColor("#00D2D3"))
        setupChart(chart2, Color.parseColor("#10AC84"))
        setupChart(chart3, Color.parseColor("#FF6B6B"))

        // شروع اتصال به ESP32
        startTcpConnection()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Settings")?.setIcon(android.R.drawable.ic_menu_preferences)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            showSettingsDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = dialogView.findViewById<EditText>(R.id.etIpAddress)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)

        etIp.setText(espIp)
        etPort.setText(espPort.toString())

        AlertDialog.Builder(this)
            .setTitle("Connection Settings")
            .setView(dialogView)
            .setPositiveButton("Save & Reconnect") { _, _ ->
                espIp = etIp.text.toString().trim()
                espPort = etPort.text.toString().trim().toIntOrNull() ?: 8888
                saveSettings()
                
                // قطع اتصال فعلی و شروع اتصال جدید
                stopTcpConnection()
                startTcpConnection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("IP", espIp)
            putInt("PORT", espPort)
            apply()
        }
        Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        espIp = prefs.getString("IP", "192.168.4.1") ?: "192.168.4.1"
        espPort = prefs.getInt("PORT", 8888)
    }

    private fun setupChart(chart: LineChart, colorHex: Int) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)

        chart.xAxis.isEnabled = false
        chart.axisRight.isEnabled = false

        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.parseColor("#8A94A6")
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = Color.parseColor("#252932")
        leftAxis.textSize = 9f

        val entries = ArrayList<Entry>()
        val dataSet = LineDataSet(entries, "Temp")
        dataSet.color = colorHex
        dataSet.lineWidth = 2f
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER

        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun startTcpConnection() {
        if (isConnected) return
        updateStatus("Connecting to ESP32...", Color.parseColor("#E1B12C"))

        connectionThread = thread {
            try {
                tcpSocket = Socket(espIp, espPort)
                tcpSocket?.soTimeout = 5000 // قطع اتصال در صورت عدم دریافت داده پس از 5 ثانیه
                isConnected = true
                
                mainHandler.post {
                    updateStatus("Connected", Color.parseColor("#10AC84"))
                }

                val reader = BufferedReader(InputStreamReader(tcpSocket?.getInputStream()))
                while (isConnected) {
                    val line = reader.readLine()
                    if (line != null) {
                        parseEspData(line)
                    } else {
                        throw Exception("Connection lost")
                    }
                }
            } catch (e: Exception) {
                isConnected = false
                mainHandler.post {
                    updateStatus("CONNECTION FAILED\n(${e.message})", Color.parseColor("#FF6B6B"))
                }
                // تلاش مجدد بعد از 3 ثانیه
                Thread.sleep(3000)
                if (!isDestroyed) {
                    startTcpConnection()
                }
            } finally {
                stopTcpConnection()
            }
        }
    }

    private fun stopTcpConnection() {
        isConnected = false
        try {
            tcpSocket?.close()
        } catch (e: Exception) {}
        tcpSocket = null
    }

    private fun parseEspData(payload: String) {
        // نمونه داده ESP32: T1:25.40C|T2:26.10C|T3:24.80C
        mainHandler.post {
            try {
                val parts = payload.split("|")
                for (part in parts) {
                    val cleanPart = part.replace("C", "").trim()
                    if (cleanPart.startsWith("T1:")) {
                        val v = cleanPart.substring(3).toFloatOrNull()
                        if (v != null) {
                            tvSensor1.text = String.format("%.2f °C", v)
                            addEntry(chart1, v, 1)
                        }
                    } else if (cleanPart.startsWith("T2:")) {
                        val v = cleanPart.substring(3).toFloatOrNull()
                        if (v != null) {
                            tvSensor2.text = String.format("%.2f °C", v)
                            addEntry(chart2, v, 2)
                        }
                    } else if (cleanPart.startsWith("T3:")) {
                        val v = cleanPart.substring(3).toFloatOrNull()
                        if (v != null) {
                            tvSensor3.text = String.format("%.2f °C", v)
                            addEntry(chart3, v, 3)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addEntry(chart: LineChart, value: Float, sensorId: Int) {
        val data = chart.data ?: return
        val dataSet = data.getDataSetByIndex(0) ?: return

        var index = 0f
        when (sensorId) {
            1 -> { index = chartIndex1; chartIndex1++ }
            2 -> { index = chartIndex2; chartIndex2++ }
            3 -> { index = chartIndex3; chartIndex3++ }
        }

        data.addEntry(Entry(index, value), 0)

        if (dataSet.entryCount > maxEntries) {
            dataSet.removeEntry(0)
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxEntries.toFloat())
        chart.moveViewToX(data.entryCount.toFloat())
    }

    private fun updateStatus(msg: String, color: Int) {
        tvConnStatus.text = msg
        tvConnStatus.setTextColor(color)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTcpConnection()
    }
}
