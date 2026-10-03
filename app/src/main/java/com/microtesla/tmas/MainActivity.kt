package com.microtesla.tmas

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvSensor1: TextView
    private lateinit var tvSensor2: TextView
    private lateinit var tvSensor3: TextView
    private lateinit var btnSettings: Button

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private val maxEntries = 40
    private var chartIndex1 = 0f
    private var chartIndex2 = 0f
    private var chartIndex3 = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    @Volatile
    private var isRunning = false
    private var socket: Socket? = null
    private var tcpThread: Thread? = null

    // SMS alert throttling (minimum 60s interval between alerts)
    private var lastSmsSentTime: Long = 0
    private val SMS_COOLDOWN_MS = 60000L
    private val SMS_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("TMAS_CONFIG", Context.MODE_PRIVATE)

        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvSensor1 = findViewById(R.id.tvSensor1)
        tvSensor2 = findViewById(R.id.tvSensor2)
        tvSensor3 = findViewById(R.id.tvSensor3)
        btnSettings = findViewById(R.id.btnSettings)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        setupChart(chart1, Color.parseColor("#00D2D3"))
        setupChart(chart2, Color.parseColor("#10AC84"))
        setupChart(chart3, Color.parseColor("#FF6B6B"))

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        checkAndRequestPermissions()
        startTcpClient()
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION_CODE
            )
        }
    }

    private fun setupChart(chart: LineChart, lineColor: Int) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.legend.isEnabled = false

        val xAxis = chart.xAxis
        xAxis.textColor = Color.parseColor("#555E6D")
        xAxis.textSize = 9f
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = Color.parseColor("#1F242E")

        val yAxisLeft = chart.axisLeft
        yAxisLeft.textColor = Color.parseColor("#555E6D")
        yAxisLeft.textSize = 9f
        yAxisLeft.setDrawGridLines(true)
        yAxisLeft.gridColor = Color.parseColor("#1F242E")
        yAxisLeft.axisMinimum = 0f
        yAxisLeft.axisMaximum = 100f

        chart.axisRight.isEnabled = false

        val entries = ArrayList<Entry>()
        val dataSet = LineDataSet(entries, "Temperature")
        dataSet.color = lineColor
        dataSet.lineWidth = 2.2f
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER

        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun updateChart(chart: LineChart, value: Float, sensorIndex: Int) {
        val data = chart.data ?: return
        var set = data.getDataSetByIndex(0)
        if (set == null) {
            set = LineDataSet(ArrayList(), "Temp")
            data.addDataSet(set)
        }

        val index = when (sensorIndex) {
            1 -> { chartIndex1++; chartIndex1 }
            2 -> { chartIndex2++; chartIndex2 }
            else -> { chartIndex3++; chartIndex3 }
        }

        data.addEntry(Entry(index, value), 0)
        if (set.entryCount > maxEntries) {
            set.removeFirst()
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxEntries.toFloat())
        chart.moveViewToX(index)
    }

    private fun startTcpClient() {
        isRunning = true
        tcpThread = thread(start = true, name = "TCP_Client_Thread") {
            while (isRunning) {
                val ip = prefs.getString("tcp_ip", "192.168.4.1") ?: "192.168.4.1"
                val port = prefs.getInt("tcp_port", 8888)

                updateConnectionStatus("Connecting...", Color.parseColor("#F39C12"))

                try {
                    socket = Socket()
                    socket?.connect(InetSocketAddress(ip, port), 4000)
                    socket?.soTimeout = 6000

                    updateConnectionStatus("Connected", Color.parseColor("#10AC84"))

                    val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

                    while (isRunning && socket?.isConnected == true && !socket!!.isClosed) {
                        val line = reader.readLine() ?: break
                        if (line.isNotEmpty()) {
                            parseAndHandleData(line.trim())
                        }
                    }
                } catch (e: Exception) {
                    // Connection dropped or timeout
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                    socket = null
                    updateConnectionStatus("Disconnected", Color.parseColor("#FF6B6B"))
                }

                if (isRunning) {
                    try {
                        Thread.sleep(2500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun parseAndHandleData(raw: String) {
        // Expected payload format: "T1:25.40C|T2:26.10C|T3:24.80C" or standard delimited format
        var t1: Float? = null
        var t2: Float? = null
        var t3: Float? = null

        val parts = raw.split("|")
        for (part in parts) {
            val p = part.trim()
            if (p.startsWith("T1:")) {
                t1 = p.removePrefix("T1:").replace("C", "").trim().toFloatOrNull()
            } else if (p.startsWith("T2:")) {
                t2 = p.removePrefix("T2:").replace("C", "").trim().toFloatOrNull()
            } else if (p.startsWith("T3:")) {
                t3 = p.removePrefix("T3:").replace("C", "").trim().toFloatOrNull()
            }
        }

        mainHandler.post {
            t1?.let {
                tvSensor1.text = String.format("%.1f °C", it)
                updateChart(chart1, it, 1)
            }
            t2?.let {
                tvSensor2.text = String.format("%.1f °C", it)
                updateChart(chart2, it, 2)
            }
            t3?.let {
                tvSensor3.text = String.format("%.1f °C", it)
                updateChart(chart3, it, 3)
            }

            checkAndTriggerAlert(t1, t2, t3)
        }
    }

    private fun checkAndTriggerAlert(t1: Float?, t2: Float?, t3: Float?) {
        val smsEnabled = prefs.getBoolean("sms_enabled", false)
        if (!smsEnabled) return

        val threshold = prefs.getFloat("temp_threshold", 50.0f)
        val phone = prefs.getString("alert_phone", "") ?: ""

        if (phone.isBlank()) return

        var breached = false
        val alertList = mutableListOf<String>()

        t1?.let { if (it >= threshold) { breached = true; alertList.add("T1: ${it}°C") } }
        t2?.let { if (it >= threshold) { breached = true; alertList.add("T2: ${it}°C") } }
        t3?.let { if (it >= threshold) { breached = true; alertList.add("T3: ${it}°C") } }

        if (breached) {
            val now = System.currentTimeMillis()
            if (now - lastSmsSentTime > SMS_COOLDOWN_MS) {
                lastSmsSentTime = now
                sendSms(phone, "[TMAS Alert] High Temperature Detected! " + alertList.joinToString(", "))
            }
        }
    }

    private fun sendSms(phoneNumber: String, message: String) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                Toast.makeText(this, "Alert SMS sent to $phoneNumber", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SMS Permission not granted", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateConnectionStatus(status: String, color: Int) {
        mainHandler.post {
            tvConnStatus.text = status
            tvConnStatus.setTextColor(color)
        }
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etIpAddress)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etThreshold = view.findViewById<EditText>(R.id.etThreshold)
        val cbEnableSms = view.findViewById<CheckBox>(R.id.cbEnableSms)
        val etPhone = view.findViewById<EditText>(R.id.etPhoneNumber)

        etIp.setText(prefs.getString("tcp_ip", "192.168.4.1"))
        etPort.setText(prefs.getInt("tcp_port", 8888).toString())
        etThreshold.setText(prefs.getFloat("temp_threshold", 50.0f).toString())
        cbEnableSms.isChecked = prefs.getBoolean("sms_enabled", false)
        etPhone.setText(prefs.getString("alert_phone", ""))

        AlertDialog.Builder(this, R.style.Theme_AppCompat_Dialog_Alert)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val ip = etIp.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: 8888
                val threshold = etThreshold.text.toString().toFloatOrNull() ?: 50.0f
                val sms = cbEnableSms.isChecked
                val phone = etPhone.text.toString().trim()

                prefs.edit()
                    .putString("tcp_ip", ip)
                    .putInt("tcp_port", port)
                    .putFloat("temp_threshold", threshold)
                    .putBoolean("sms_enabled", sms)
                    .putString("alert_phone", phone)
                    .apply()

                Toast.makeText(this, "Settings saved. Reconnecting...", Toast.LENGTH_SHORT).show()
                // Force reconnect socket
                thread {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        tcpThread?.interrupt()
    }
}
