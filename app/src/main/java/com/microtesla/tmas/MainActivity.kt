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
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import org.json.JSONObject
import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvSensor1: TextView
    private lateinit var tvSensor2: TextView
    private lateinit var tvSensor3: TextView
    private lateinit var btnSettings: ImageView

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private var xValue = 0f
    private val maxDataPoints = 30

    // تنظیمات شبکه TCP
    private val espIpAddress = "192.168.4.1"
    private val espPort = 8888
    private var tcpSocket: Socket? = null
    private var isTcpRunning = false

    // SMS & Settings
    private lateinit var sharedPrefs: SharedPreferences
    private val SMS_PERMISSION_CODE = 101
    
    // Cooldown Logic
    private var lastSmsTimeSensor1: Long = 0
    private var lastSmsTimeSensor2: Long = 0
    private var lastSmsTimeSensor3: Long = 0
    private val SMS_COOLDOWN_MS: Long = 60000 // 60 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("TMAS_CONFIG", Context.MODE_PRIVATE)

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

        // Bind network to WiFi to bypass mobile data routing issue
        bindToWifi()

        // کلیک روی دکمه تنظیمات
        btnSettings.setOnClickListener {
            showConfigDialog()
        }

        // درخواست دسترسی SMS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_CODE)
        }
    }

    private fun bindToWifi() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                connectivityManager.bindProcessToNetwork(network)
                Log.d("Network", "Bound to WiFi network")
                startTcpClient() // اتصال بعد از بایند شدن به وای‌فای
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d("Network", "WiFi network lost")
                updateConnectionStatus("Disconnected", Color.parseColor("#FF6B6B"))
                isTcpRunning = false
            }
        })
    }

    private fun startTcpClient() {
        if (isTcpRunning) return
        isTcpRunning = true
        
        thread {
            try {
                runOnUiThread { updateConnectionStatus("Connecting...", Color.parseColor("#FF9F43")) }
                tcpSocket = Socket(espIpAddress, espPort)
                runOnUiThread { updateConnectionStatus("● Live TCP", Color.parseColor("#10AC84")) }
                
                val inputStream: InputStream = tcpSocket!!.getInputStream()
                val buffer = ByteArray(1024)
                
                while (isTcpRunning && tcpSocket?.isConnected == true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead != -1) {
                        val message = String(buffer, 0, bytesRead).trim()
                        if (message.isNotEmpty()) {
                            parseJsonMessage(message)
                        }
                    } else {
                        break // Connection closed
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isTcpRunning = false
                try { tcpSocket?.close() } catch (e: Exception) {}
                runOnUiThread { 
                    updateConnectionStatus("Disconnected", Color.parseColor("#FF6B6B")) 
                }
                // تلاش مجدد بعد از 3 ثانیه
                Thread.sleep(3000)
                if(!isDestroyed) startTcpClient()
            }
        }
    }

    private fun updateConnectionStatus(text: String, color: Int) {
        tvConnStatus.text = text
        tvConnStatus.setTextColor(color)
    }

    private fun setupChart(chart: LineChart, lineColor: Int) {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setDrawGridBackground(false)
            
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = Color.WHITE
            }
            
            axisLeft.apply {
                textColor = Color.WHITE
                setDrawGridLines(true)
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
            legend.isEnabled = false

            data = LineData(LineDataSet(ArrayList<Entry>(), "").apply {
                color = lineColor
                setDrawCircles(false)
                lineWidth = 2f
                mode = LineDataSet.Mode.CUBIC_BEZIER
            })
        }
    }

    private fun parseJsonMessage(message: String) {
        try {
            // ممکن است چندین JSON پشت هم بیاید، پردازش آخرین مورد
            val jsonParts = message.split("\n").filter { it.isNotBlank() }
            for (part in jsonParts) {
                val jsonObject = JSONObject(part)
                val temp1 = jsonObject.optDouble("temp1", 0.0)
                val temp2 = jsonObject.optDouble("temp2", 0.0)
                val temp3 = jsonObject.optDouble("temp3", 0.0)

                runOnUiThread {
                    tvSensor1.text = String.format("%.2f °C", temp1)
                    tvSensor2.text = String.format("%.2f °C", temp2)
                    tvSensor3.text = String.format("%.2f °C", temp3)

                    addEntryToChart(chart1, temp1.toFloat())
                    addEntryToChart(chart2, temp2.toFloat())
                    addEntryToChart(chart3, temp3.toFloat())
                    
                    xValue++
                    checkThresholdsAndAlert(temp1, temp2, temp3)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addEntryToChart(chart: LineChart, value: Float) {
        val data = chart.data
        if (data != null) {
            var set = data.getDataSetByIndex(0)
            if (set == null) {
                set = LineDataSet(ArrayList<Entry>(), "")
                data.addDataSet(set)
            }
            
            data.addEntry(Entry(xValue, value), 0)
            data.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.setVisibleXRangeMaximum(maxDataPoints.toFloat())
            chart.moveViewToX(data.entryCount.toFloat())
        }
    }

    // منطق بررسی آستانه دما و ارسال پیامک
    private fun checkThresholdsAndAlert(t1: Double, t2: Double, t3: Double) {
        val thr1 = sharedPrefs.getFloat("threshold1", 50.0f).toDouble()
        val thr2 = sharedPrefs.getFloat("threshold2", 50.0f).toDouble()
        val thr3 = sharedPrefs.getFloat("threshold3", 50.0f).toDouble()

        val currentTime = System.currentTimeMillis()

        if (t1 > thr1 && (currentTime - lastSmsTimeSensor1 > SMS_COOLDOWN_MS)) {
            sendSmsAlert("سنسور 1", t1)
            lastSmsTimeSensor1 = currentTime
        }
        if (t2 > thr2 && (currentTime - lastSmsTimeSensor2 > SMS_COOLDOWN_MS)) {
            sendSmsAlert("سنسور 2", t2)
            lastSmsTimeSensor2 = currentTime
        }
        if (t3 > thr3 && (currentTime - lastSmsTimeSensor3 > SMS_COOLDOWN_MS)) {
            sendSmsAlert("سنسور 3", t3)
            lastSmsTimeSensor3 = currentTime
        }
    }

    private fun sendSmsAlert(sensorName: String, temp: Double) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val phone1 = sharedPrefs.getString("phone1", "")
        val phone2 = sharedPrefs.getString("phone2", "")
        val phone3 = sharedPrefs.getString("phone3", "")

        val enableAdmin2 = sharedPrefs.getBoolean("enable_admin2", false)
        val enableAdmin3 = sharedPrefs.getBoolean("enable_admin3", false)

        val message = "هشدار TMAS: دمای $sensorName از حد مجاز عبور کرد! دمای فعلی: ${String.format("%.1f", temp)} درجه."

        val smsManager = SmsManager.getDefault()
        try {
            if (!phone1.isNullOrEmpty()) smsManager.sendTextMessage(phone1, null, message, null, null)
            if (enableAdmin2 && !phone2.isNullOrEmpty()) smsManager.sendTextMessage(phone2, null, message, null, null)
            if (enableAdmin3 && !phone3.isNullOrEmpty()) smsManager.sendTextMessage(phone3, null, message, null, null)
            
            Toast.makeText(this, "SMS Alert Sent!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // دیالوگ تنظیمات داینامیک بدون نیاز به فایل XML خارجی
    private fun showConfigDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        // --- Manager 1 ---
        val etPhone1 = EditText(this).apply {
            hint = "Manager 1 Phone"
            setText(sharedPrefs.getString("phone1", ""))
        }
        layout.addView(etPhone1)

        // --- Manager 2 ---
        val cbAdmin2 = CheckBox(this).apply {
            text = "Enable Manager 2"
            isChecked = sharedPrefs.getBoolean("enable_admin2", false)
        }
        val etPhone2 = EditText(this).apply {
            hint = "Manager 2 Phone"
            setText(sharedPrefs.getString("phone2", ""))
        }
        layout.addView(cbAdmin2)
        layout.addView(etPhone2)

        // --- Manager 3 ---
        val cbAdmin3 = CheckBox(this).apply {
            text = "Enable Manager 3"
            isChecked = sharedPrefs.getBoolean("enable_admin3", false)
        }
        val etPhone3 = EditText(this).apply {
            hint = "Manager 3 Phone"
            setText(sharedPrefs.getString("phone3", ""))
        }
        layout.addView(cbAdmin3)
        layout.addView(etPhone3)

        // --- Thresholds ---
        val tvThr = TextView(this).apply {
            text = "\nTemperature Thresholds (°C)"
            setTextColor(Color.BLACK)
            textSize = 16f
        }
        layout.addView(tvThr)

        val etThr1 = EditText(this).apply {
            hint = "Sensor 1 Max Temp"
            setText(sharedPrefs.getFloat("threshold1", 50.0f).toString())
        }
        val etThr2 = EditText(this).apply {
            hint = "Sensor 2 Max Temp"
            setText(sharedPrefs.getFloat("threshold2", 50.0f).toString())
        }
        val etThr3 = EditText(this).apply {
            hint = "Sensor 3 Max Temp"
            setText(sharedPrefs.getFloat("threshold3", 50.0f).toString())
        }
        layout.addView(etThr1)
        layout.addView(etThr2)
        layout.addView(etThr3)

        // --- Dialog Builder ---
        val builder = AlertDialog.Builder(this)
        builder.setTitle("System Configuration")
        builder.setView(layout)
        builder.setPositiveButton("Save") { _, _ ->
            sharedPrefs.edit().apply {
                putString("phone1", etPhone1.text.toString())
                putString("phone2", etPhone2.text.toString())
                putString("phone3", etPhone3.text.toString())

                putBoolean("enable_admin2", cbAdmin2.isChecked)
                putBoolean("enable_admin3", cbAdmin3.isChecked)

                putFloat("threshold1", etThr1.text.toString().toFloatOrNull() ?: 50.0f)
                putFloat("threshold2", etThr2.text.toString().toFloatOrNull() ?: 50.0f)
                putFloat("threshold3", etThr3.text.toString().toFloatOrNull() ?: 50.0f)

                apply()
            }
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isTcpRunning = false
        try { tcpSocket?.close() } catch (e: Exception) {}
    }
}
