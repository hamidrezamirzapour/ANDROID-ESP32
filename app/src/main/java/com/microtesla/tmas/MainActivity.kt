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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvSensor1: TextView
    private lateinit var tvSensor2: TextView
    private lateinit var tvSensor3: TextView
    private var btnSettings: ImageButton? = null

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private val maxEntries = 30
    private var chartIndex1 = 0f
    private var chartIndex2 = 0f
    private var chartIndex3 = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    // تنظیمات TCP Socket
    private val tcpHost = "192.168.4.1"
    private val tcpPort = 8888
    private var isTcpRunning = false
    private var tcpSocket: Socket? = null
    private var tcpThread: Thread? = null

    // متغیرهای SharedPreferences برای ذخیره تنظیمات
    private lateinit var prefs: SharedPreferences
    private val PREFS_NAME = "TMAS_Settings"
    private val KEY_M3_PHONE = "m3_phone"
    private val KEY_S1_MIN = "s1_min"
    private val KEY_S1_MAX = "s1_max"
    private val KEY_S2_MIN = "s2_min"
    private val KEY_S2_MAX = "s2_max"
    private val KEY_S3_MIN = "s3_min"
    private val KEY_S3_MAX = "s3_max"
    private val KEY_M2_SMS_ENABLE = "m2_sms_enable"
    private val KEY_M3_SMS_ENABLE = "m3_sms_enable"

    // Flag های سیستم برای جلوگیری از ارسال رگباری پیامک
    private var isS1InAlarm = false
    private var isS2InAlarm = false
    private var isS3InAlarm = false

    private val SMS_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvSensor1 = findViewById(R.id.tvSensor1)
        tvSensor2 = findViewById(R.id.tvSensor2)
        tvSensor3 = findViewById(R.id.tvSensor3)

        // دکمه تنظیمات (باید در activity_main.xml شما وجود داشته باشد)
        btnSettings = findViewById(R.id.btnSettings)
        btnSettings?.setOnClickListener {
            showSettingsDialog()
        }

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        setupChart(chart1, Color.parseColor("#00D2D3"))
        setupChart(chart2, Color.parseColor("#10AC84"))
        setupChart(chart3, Color.parseColor("#FF6B6B"))

        checkSmsPermission()
        startTcpClient()
    }

    private fun checkSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION_CODE
            )
        }
    }

    private fun setupChart(chart: LineChart, color: Int) {
        val dataSet = LineDataSet(mutableListOf(), "Temp").apply {
            this.color = color
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        chart.data = LineData(dataSet)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)

        chart.xAxis.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            textColor = Color.parseColor("#8A94A6")
            gridColor = Color.parseColor("#252932")
            textSize = 9f
        }
        chart.invalidate()
    }

    private fun addEntryToChart(chart: LineChart, value: Float, index: Float): Float {
        val data = chart.data ?: return index
        val set = data.getDataSetByIndex(0) ?: return index

        data.addEntry(Entry(index, value), 0)
        if (set.entryCount > maxEntries) {
            set.removeFirst()
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxEntries.toFloat())
        chart.moveViewToX(index)
        chart.invalidate()
        return index + 1f
    }

    // --- اتصال به سوکت TCP روی ESP32 ---
    private fun startTcpClient() {
        isTcpRunning = true
        tcpThread = Thread {
            while (isTcpRunning) {
                try {
                    mainHandler.post {
                        tvConnStatus.text = "Connecting..."
                        tvConnStatus.setTextColor(Color.parseColor("#FFA500"))
                    }

                    tcpSocket = Socket()
                    tcpSocket?.connect(InetSocketAddress(tcpHost, tcpPort), 5000)

                    mainHandler.post {
                        tvConnStatus.text = "● Live TCP"
                        tvConnStatus.setTextColor(Color.parseColor("#10AC84"))
                    }

                    val reader = BufferedReader(InputStreamReader(tcpSocket?.getInputStream()))
                    var line: String?

                    while (isTcpRunning && reader.readLine().also { line = it } != null) {
                        line?.let { data ->
                            mainHandler.post {
                                parseAndProcessData(data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        tvConnStatus.text = "Disconnected"
                        tvConnStatus.setTextColor(Color.parseColor("#FF6B6B"))
                    }
                } finally {
                    try {
                        tcpSocket?.close()
                    } catch (ignored: Exception) {}
                }

                // تاخیر قبل از تلاش مج, value), 0)
        if (set.entryCount > maxEntries) {
            set.removeFirst()
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxEntries.toFloat())
        chart.moveViewToX(index)
        chart.invalidate()
        return index + 1f
    }

    // --- اتصال به سوکت TCP روی ESP32 ---
    private fun startTcpClient() {
        isTcpRunning = true
        tcpThread = Thread {
            while (isTcpRunning) {
                try {
                    mainHandler.post {
                        tvConnStatus.text = "Connecting..."
                        tvConnStatus.setTextColor(Color.parseColor("#FFA500"))
                    }

                    tcpSocket = Socket()
                    tcpSocket?.connect(InetSocketAddress(tcpHost, tcpPort), 5000)

                    mainHandler.post {
                        tvConnStatus.text = "● Live TCP"
                        tvConnStatus.setTextColor(Color.parseColor("#10AC84"))
                    }

                    val reader = BufferedReader(InputStreamReader(tcpSocket?.getInputStream()))
                    var line: String?

                    while (isTcpRunning && reader.readLine().also { line = it } != null) {
                        line?.let { data ->
                            mainHandler.post {
                                parseAndProcessData(data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        tvConnStatus.text = "Disconnected"
                        tvConnStatus.setTextColor(Color.parseColor("#FF6B6B"))
                    }
                } finally {
                    try {
                        tcpSocket?.close()
                    } catch (ignored: Exception) {}
                }

                // تاخیر قبل از تلاش مجدد برای اتصال
                if (isTcpRunning) {
                    try {
                        Thread.sleep(30, it, chartIndex3)
                    checkThresholds("Sensor 3", it, KEY_S3_MIN, KEY_S3_MAX, 3)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- بررسی آستانه‌های مجاز و مدیریت آلارم ---
    private fun checkThresholds(sensorName: String, value: Float, minKey: String, maxKey: String, sensorIndex: Int) {
        val minStr = prefs.getString(minKey, "") ?: ""
        val maxStr = prefs.getString(maxKey, "") ?: ""

        val minVal = minStr.toFloatOrNull()
        val maxVal = maxStr.toFloatOrNull()

        var isAlarm = false
        var reason = ""

        if (minVal != null && value < minVal) {
            isAlarm = true
            reason = "LOW ($value < $minVal)"
        } else if (maxVal != null && value > maxVal) {
            isAlarm = true
            reason = "HIGH ($value > $maxVal)"
        }

        when (sensorIndex) {
            1 -> {
                if (isAlarm && !isS1InAlarm) {
                    isS1InAlarm = true
                    triggerAlarm(sensorName, value, reason)
                } else if (!isAlarm && isS1InAlarm) {
                    isS1InAlarm = false
                }
            }
            2 -> {
                if (isAlarm && !isS2InAlarm) {
                    isS2InAlarm = true
                    triggerAlarm(sensorName, value, reason)
                } else if (!isAlarm && isS2InAlarm) {
                    isS2InAlarm = false
                }
            }
            3 -> {
                if (isAlarm && !isS3InAlarm) {
                    isS3InAlarm = true
                    triggerAlarm(sensorName, value, reason)
                } else if (!isAlarm && isS3InAlarm) {
                    isS3InAlarm = false
                }
            }
        }
    }

    private fun triggerAlarm(sensorName: String, value: Float, reason: String) {
        val message = "⚠️ TMAS هشدار: $sensorName خارج از محدوده است!\nدمای فعلی: $value°C ($reason)"
        
        val isM3Enabled = prefs.getBoolean(KEY_M3_SMS_ENABLE, true)
        val m3Phone = prefs.getString(KEY_M3_PHONE, "") ?: ""

        if (isM3Enabled && m3Phone.isNotEmpty()) {
            sendSms(m3Phone, message)
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun sendSms(phone: String, msg: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phone, null, msg, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- نمایش و ذخیره دیالوگ تنظیمات ---
    private fun showSettingsDialog() {
        // توجه: شما باید فایل dialog_settings.xml را با المنت‌های مربوطه ایجاد کنید
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        
        val etM3Phone = dialogView.findViewById<EditText>(R.id.etM3Phone)
        val etS1Min = dialogView.findViewById<EditText>(R.id.etS1Min)
        val etS1Max = dialogView.findViewById<EditText>(R.id.etS1Max)
        val etS2Min = dialogView.findViewById<EditText>(R.id.etS2Min)
        val etS2Max = dialogView.findViewById<EditText>(R.id.etS2Max)
        val etS3Min = dialogView.findViewById<EditText>(R.id.etS3Min)
        val etS3Max = dialogView.findViewById<EditText>(R.id.etS3Max)
        val switchM2 = dialogView.findViewById<SwitchCompat>(R.id.switchM2)
        val switchM3 = dialogView.findViewById<SwitchCompat>(R.id.switchM3)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        // مقداردهی اولیه از تنظیمات قبلی
        etM3Phone.setText(prefs.getString(KEY_M3_PHONE, ""))
        etS1Min.setText(prefs.getString(KEY_S1_MIN, ""))
        etS1Max.setText(prefs.getString(KEY_S1_MAX, ""))
        etS2Min.setText(prefs.getString(KEY_S2_MIN, ""))
        etS2Max.setText(prefs.getString(KEY_S2_MAX, ""))
        etS3Min.setText(prefs.getString(KEY_S3_MIN, ""))
        etS3Max.setText(prefs.getString(KEY_S3_MAX, ""))
        switchM2.isChecked = prefs.getBoolean(KEY_M2_SMS_ENABLE, false)
        switchM3.isChecked = prefs.getBoolean(KEY_M3_SMS_ENABLE, true)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString(KEY_M3_PHONE, etM3Phone.text.toString().trim())
                putString(KEY_S1_MIN, etS1Min.text.toString().trim())
                putString(KEY_S1_MAX, etS1Max.text.toString().trim())
                putString(KEY_S2_MIN, etS2Min.text.toString().trim())
                putString(KEY_S2_MAX, etS2Max.text.toString().trim())
                putString(KEY_S3_MIN, etS3Min.text.toString().trim())
                putString(KEY_S3_MAX, etS3Max.text.toString().trim())
                putBoolean(KEY_M2_SMS_ENABLE, switchM2.isChecked)
                putBoolean(KEY_M3_SMS_ENABLE, switchM3.isChecked)
                apply()
            }
            Toast.makeText(this, "تنظیمات با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isTcpRunning = false
        try {
            tcpSocket?.close()
        } catch (ignored: Exception) {}
        tcpThread?.interrupt()
    }
}
