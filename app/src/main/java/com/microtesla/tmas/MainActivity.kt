package com.microtesla.tmas

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvSensor1: TextView
    private lateinit var tvSensor2: TextView
    private lateinit var tvSensor3: TextView
    private lateinit var btnSettings: Button

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private var timeIndex = 0f
    @Volatile private var isRunning = false
    private var clientSocket: Socket? = null

    private val espIp = "192.168.4.1"
    private val espPort = 8888

    // ذخیره دما برای پیامک‌های جواب‌دهی
    private var currentT1 = 0f
    private var currentT2 = 0f
    private var currentT3 = 0f

    // تایمر جلوگیری از ارسال رگباری پیامک (مثلا 3 دقیقه معادل 180,000 میلی‌ثانیه)
    private var lastAlertTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // گرفتن دسترسی‌های پیامک از کاربر در صورت نیاز
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
            101
        )

        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvSensor1 = findViewById(R.id.tvSensor1)
        tvSensor2 = findViewById(R.id.tvSensor2)
        tvSensor3 = findViewById(R.id.tvSensor3)
        btnSettings = findViewById(R.id.btnSettings)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupChart(chart1, Color.parseColor("#00D2D3"))
        setupChart(chart2, Color.parseColor("#10AC84"))
        setupChart(chart3, Color.parseColor("#FF6B6B"))

        startTcpClient()

        // رجیستر کردن رسیور پیامک‌ها برای گوش دادن به دستورات
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        registerReceiver(smsReceiver, filter)
    }

    private fun setupChart(chart: LineChart, color: Int) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setDrawGridBackground(false)
        chart.legend.isEnabled = false

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.setDrawLabels(false)
        xAxis.textColor = Color.LTGRAY

        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.LTGRAY
        leftAxis.setGridColor(Color.parseColor("#333333"))
        leftAxis.setDrawZeroLine(false)
        leftAxis.setStartAtZero(false)
        chart.axisRight.isEnabled = false

        val dataSet = LineDataSet(ArrayList(), "Temp").apply {
            this.color = color
            lineWidth = 2.5f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun startTcpClient() {
        isRunning = true
        Thread {
            while (isRunning) {
                try {
                    runOnUiThread {
                        tvConnStatus.text = "Connecting..."
                        tvConnStatus.setTextColor(Color.YELLOW)
                    }

                    val socket = Socket()
                    socket.connect(InetSocketAddress(espIp, espPort), 4000)
                    clientSocket = socket

                    runOnUiThread {
                        tvConnStatus.text = "CONNECTED"
                        tvConnStatus.setTextColor(Color.GREEN)
                    }

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    while (isRunning) {
                        val line = reader.readLine() ?: break
                        parseAndDisplay(line)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvConnStatus.text = "Disconnected"
                        tvConnStatus.setTextColor(Color.RED)
                    }
                } finally {
                    try { clientSocket?.close() } catch (_: Exception) {}
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    private fun parseAndDisplay(rawLine: String) {
        try {
            val parts = rawLine.trim().split("|")
            for (part in parts) {
                val clean = part.replace("C", "").trim()
                val keyValue = clean.split(":")
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim().toFloatOrNull() ?: 0f
                    when (key) {
                        "T1", "S1" -> currentT1 = value
                        "T2", "S2" -> currentT2 = value
                        "T3", "S3" -> currentT3 = value
                    }
                }
            }

            runOnUiThread {
                tvSensor1.text = String.format("%.2f °C", currentT1)
                tvSensor2.text = String.format("%.2f °C", currentT2)
                tvSensor3.text = String.format("%.2f °C", currentT3)

                addEntryToChart(chart1, currentT1, timeIndex)
                addEntryToChart(chart2, currentT2, timeIndex)
                addEntryToChart(chart3, currentT3, timeIndex)
                timeIndex += 1f
            }

            checkAlarms()

        } catch (_: Exception) {}
    }

    private fun checkAlarms() {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        
        val s1Min = prefs.getFloat("S1_MIN", 0f)
        val s1Max = prefs.getFloat("S1_MAX", 50f)
        val s2Min = prefs.getFloat("S2_MIN", 0f)
        val s2Max = prefs.getFloat("S2_MAX", 50f)
        val s3Min = prefs.getFloat("S3_MIN", 0f)
        val s3Max = prefs.getFloat("S3_MAX", 50f)

        var alarmMsg = ""
        if (currentT1 < s1Min || currentT1 > s1Max) alarmMsg += "سنسور ۱: $currentT1 C\n"
        if (currentT2 < s2Min || currentT2 > s2Max) alarmMsg += "سنسور ۲: $currentT2 C\n"
        if (currentT3 < s3Min || currentT3 > s3Max) alarmMsg += "سنسور ۳: $currentT3 C\n"

        if (alarmMsg.isNotEmpty()) {
            val now = System.currentTimeMillis()
            // پیامک هشدار با کولدان ۳ دقیقه‌ای
            if (now - lastAlertTime > 180000) {
                sendSmsToManagers("هشدار دما سیستم TMAS!\n" + alarmMsg)
                lastAlertTime = now
            }
        }
    }

    private fun sendSmsToManagers(message: String) {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        val m1 = prefs.getString("M1", "") ?: ""
        val m2 = prefs.getString("M2", "") ?: ""
        val m2En = prefs.getBoolean("M2_EN", false)
        val m3 = prefs.getString("M3", "") ?: ""
        val m3En = prefs.getBoolean("M3_EN", false)

        val smsManager = SmsManager.getDefault()
        try {
            if (m1.isNotEmpty()) smsManager.sendTextMessage(m1, null, message, null, null)
            if (m2.isNotEmpty() && m2En) smsManager.sendTextMessage(m2, null, message, null, null)
            if (m3.isNotEmpty() && m3En) smsManager.sendTextMessage(m3, null, message, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
                val bundle = intent.extras
                if (bundle != null) {
                    val pdus = bundle.get("pdus") as Array<*>
                    for (pdu in pdus
