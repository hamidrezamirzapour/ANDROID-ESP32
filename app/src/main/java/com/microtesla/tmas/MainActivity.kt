package com.microtesla.tmas

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    // --- متغیرهای تنظیمات و پیامک ---
    private lateinit var prefs: SharedPreferences
    private var phone1 = ""
    private var phone2 = ""
    private var phone3 = ""
    private var isManager2Active = false
    private var isManager3Active = false
    private var thresh1 = 50.0f
    private var thresh2 = 50.0f
    private var thresh3 = 50.0f
    private val smsCooldowns = mutableMapOf<Int, Long>()
    private val COOLDOWN_MS = 60_000L // وقفه 1 دقیقه‌ای برای جلوگیری از ارسال رگباری پیامک

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

        // بارگذاری تنظیمات ذخیره شده
        prefs = getSharedPreferences("TMAS_CONFIG", Context.MODE_PRIVATE)
        loadConfig()

        // درخواست دسترسی ارسال پیامک در زمان اجرای برنامه
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101)
        }

        // دکمه تنظیمات (از روی activity_main.xml پیدا می‌شود)
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            showConfigDialog()
        }

        // قفل کردن شبکه روی وای‌فای و شروع کلاینت TCP
        bindToWiFiAndConnect()
    }

    private fun loadConfig() {
        phone1 = prefs.getString("PHONE_1", "") ?: ""
        phone2 = prefs.getString("PHONE_2", "") ?: ""
        phone3 = prefs.getString("PHONE_3", "") ?: ""
        isManager2Active = prefs.getBoolean("MGR_2_ACTIVE", false)
        isManager3Active = prefs.getBoolean("MGR_3_ACTIVE", false)
        thresh1 = prefs.getFloat("THRESH_1", 50.0f)
        thresh2 = prefs.getFloat("THRESH_2", 50.0f)
        thresh3 = prefs.getFloat("THRESH_3", 50.0f)
    }

    // این تابع مشکل عبور ترافیک از دیتای سیم‌کارت را حل می‌کند
    private fun bindToWiFiAndConnect() {
        updateStatus("Binding to WiFi...", Color.parseColor("#FFA500"))
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // ترافیک سوکت را مجبور می‌کند فقط از روی وای‌فای رد شود
                connectivityManager.bindProcessToNetwork(network)
                mainHandler.post { startTcpClient() }
            }
        })
    }

    private fun setupChart(chart: LineChart, color: Int) {
        val dataSet = LineDataSet(mutableListOf(), "").apply {
            this.color = color
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        chart.data = LineData(dataSet)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.setDrawLabels(false)
        chart.axisLeft.apply {
            textColor = Color.WHITE
            setDrawGridLines(false)
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

                    while (isRunning) {
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
                    try { socket?.close() } catch (_: Exception) {}
                }
                
                // در صورت قطعی 3 ثانیه صبر می‌کند و دوباره متصل می‌شود
                if (isRunning) {
                    Thread.sleep(3000)
                }
            }
        }.start()
    }

    private fun parseIncomingData(data: String) {
        var val1: Float? = null
        var val2: Float? = null
        var val3: Float? = null

        try {
            val trimmed = data.trim()
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                if (json.has("s1")) val1 = json.getDouble("s1").toFloat()
                if (json.has("s2")) val2 = json.getDouble("s2").toFloat()
                if (json.has("s3")) val3 = json.getDouble("s3").toFloat()
            } else {
                val parts = trimmed.split(" | ")
                for (part in parts) {
                    val p = part.trim()
                    if (p.startsWith("T1:")) val1 = p.replace("T1:", "").replace("C", "").trim().toFloatOrNull()
                    else if (p.startsWith("T2:")) val2 = p.replace("T2:", "").replace("C", "").trim().toFloatOrNull()
                    else if (p.startsWith("T3:")) val3 = p.replace("T3:", "").replace("C", "").trim().toFloatOrNull()
                }
            }

            mainHandler.post {
                val1?.let {
                    tvSensor1.text = String.format("%.2f °C", it)
                    addEntryToChart(chart1, count1++, it)
                    checkThresholdAndSms(1, it, thresh1)
                }
                val2?.let {
                    tvSensor2.text = String.format("%.2f °C", it)
                    addEntryToChart(chart2, count2++, it)
                    checkThresholdAndSms(2, it, thresh2)
                }
                val3?.let {
                    tvSensor3.text = String.format("%.2f °C", it)
                    addEntryToChart(chart3, count3++, it)
                    checkThresholdAndSms(3, it, thresh3)
                }
            }
        } catch (e: Exception) {
            Log.e("DATA_PARSE", "Error parsing: $data", e)
        }
    }

    private fun checkThresholdAndSms(sensorId: Int, value: Float, threshold: Float) {
        if (value > threshold) {
            val now = System.currentTimeMillis()
            val lastSent = smsCooldowns[sensorId] ?: 0L
            // بررسی Cooldown (ارسال پیامک با محدودیت زمانی برای جلوگیری از اسپم)
            if (now - lastSent > COOLDOWN_MS) {
                sendSmsAlert(sensorId, value)
                smsCooldowns[sensorId] = now
            }
        }
    }

    private fun sendSmsAlert(sensorId: Int, value: Float) {
        val msg = "هشدار سیستم TMAS\nسنسور $sensorId از حد مجاز عبور کرد!\nدما: $value °C"
        try {
            val smsManager = SmsManager.getDefault()
            if (phone1.isNotEmpty()) {
                smsManager.sendTextMessage(phone1, null, msg, null, null)
            }
            if (isManager2Active && phone2.isNotEmpty()) {
                smsManager.sendTextMessage(phone2, null, msg, null, null)
            }
            if (isManager3Active && phone3.isNotEmpty()) {
                smsManager.sendTextMessage(phone3, null, msg, null, null)
            }
            Toast.makeText(this, "پیامک هشدار برای سنسور $sensorId ارسال شد", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SMS_SENDER", "Failed to send SMS", e)
        }
    }

    // ساخت دیالوگ تنظیمات (تنظیم شماره‌ها و آستانه‌ها) از طریق کد
    private fun showConfigDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("تنظیمات هشدار و مدیران")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // بخش تنظیم آستانه‌ها
        val etT1 = EditText(this).apply { hint = "آستانه سنسور 1 (°C)"; setText(thresh1.toString()) }
        val etT2 = EditText(this).apply { hint = "آستانه سنسور 2 (°C)"; setText(thresh2.toString()) }
        val etT3 = EditText(this).apply { hint = "آستانه سنسور 3 (°C)"; setText(thresh3.toString()) }
        layout.addView(TextView(this).apply { text = "آستانه دما (°C):"; setPadding(0,10,0,0) })
        layout.addView(etT1); layout.addView(etT2); layout.addView(etT3)

        // بخش تنظیم شماره مدیران
        layout.addView(TextView(this).apply { text = "شماره مدیران:"; setPadding(0,20,0,0) })
        
        val etP1 = EditText(this).apply { hint = "مدیر 1 (همیشه فعال)"; setText(phone1) }
        layout.addView(etP1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etP2 = EditText(this).apply { hint = "مدیر 2"; setText(phone2); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val cbM2 = CheckBox(this).apply { text = "فعال"; isChecked = isManager2Active }
        row2.addView(etP2); row2.addView(cbM2)
        layout.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etP3 = EditText(this).apply { hint = "مدیر 3"; setText(phone3); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val cbM3 = CheckBox(this).apply { text = "فعال"; isChecked = isManager3Active }
        row3.addView(etP3); row3.addView(cbM3)
        layout.addView(row3)

        val scroll = ScrollView(this).apply { addView(layout) }
        builder.setView(scroll)

        builder.setPositiveButton("ذخیره") { _, _ ->
            // استخراج و ذخیره مقادیر وارد شده
            thresh1 = etT1.text.toString().toFloatOrNull() ?: 50.0f
            thresh2 = etT2.text.toString().toFloatOrNull() ?: 50.0f
            thresh3 = etT3.text.toString().toFloatOrNull() ?: 50.0f
            phone1 = etP1.text.toString()
            phone2 = etP2.text.toString()
            phone3 = etP3.text.toString()
            isManager2Active = cbM2.isChecked
            isManager3Active = cbM3.isChecked

            prefs.edit().apply {
                putFloat("THRESH_1", thresh1)
                putFloat("THRESH_2", thresh2)
                putFloat("THRESH_3", thresh3)
                putString("PHONE_1", phone1)
                putString("PHONE_2", phone2)
                putString("PHONE_3", phone3)
                putBoolean("MGR_2_ACTIVE", isManager2Active)
                putBoolean("MGR_3_ACTIVE", isManager3Active)
                apply()
            }
            Toast.makeText(this, "تنظیمات ذخیره شد", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("لغو", null)
        builder.show()
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
        try { socket?.close() } catch (_: Exception) {}
    }
}
