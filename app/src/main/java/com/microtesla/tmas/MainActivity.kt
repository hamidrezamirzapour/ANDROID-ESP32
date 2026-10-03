package com.microtesla.tmas

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvTemp1: TextView
    private lateinit var tvTemp2: TextView
    private lateinit var tvTemp3: TextView
    private lateinit var tvStatus1: TextView
    private lateinit var tvStatus2: TextView
    private lateinit var tvStatus3: TextView

    private lateinit var btnSettings: Button

    private var serverIp = "192.168.4.1"
    private var serverPort = 8888
    private var alarmThreshold = 50.0f
    private var smsEnabled = false
    private var smsNumber = ""

    private var isRunning = false
    private var socket: Socket? = null

    // Handler برای اجرای کدهای UI از داخل Thread
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // پنهان کردن نوار اعلانات و حالت تمام صفحه
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)

        // متصل کردن متغیرها به المان‌های صفحه
        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvTemp1 = findViewById(R.id.tvTemp1)
        tvTemp2 = findViewById(R.id.tvTemp2)
        tvTemp3 = findViewById(R.id.tvTemp3)
        tvStatus1 = findViewById(R.id.tvStatus1)
        tvStatus2 = findViewById(R.id.tvStatus2)
        tvStatus3 = findViewById(R.id.tvStatus3)

        btnSettings = findViewById(R.id.btnSettings)

        // بارگذاری تنظیمات ذخیره شده (در صورت وجود)
        loadSettings()

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // شروع اتصال TCP
        startTcpClient()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTcpClient()
    }

    private fun startTcpClient() {
        if (isRunning) return
        isRunning = true

        updateConnectionStatus("Connecting...", Color.parseColor("#FFA500")) // نارنجی

        Thread {
            while (isRunning) {
                try {
                    socket = Socket(serverIp, serverPort)
                    socket?.soTimeout = 5000 // تایم اوت ۵ ثانیه

                    uiHandler.post {
                        updateConnectionStatus("Connected", Color.GREEN)
                    }

                    val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                    var line: String?

                    while (isRunning && socket?.isConnected == true) {
                        line = reader.readLine()
                        if (line != null) {
                            Log.d("TCP_CLIENT", "Received: $line")
                            parseData(line)
                        } else {
                            // قطع اتصال از سمت سرور
                            break
                        }
                    }

                } catch (e: Exception) {
                    Log.e("TCP_CLIENT", "Error: ${e.message}")
                    uiHandler.post {
                        updateConnectionStatus("Disconnected / Retrying...", Color.RED)
                    }
                } finally {
                    try {
                        socket?.close()
                        socket = null
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // در صورت قطع شدن، ۳ ثانیه صبر کرده و مجدد تلاش می‌کند
                if (isRunning) {
                    Thread.sleep(3000)
                }
            }
        }.start()
    }

    private fun stopTcpClient() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseData(data: String) {
        // نمونه داده دریافتی: T1:25.5C|T2:26.0C|T3:24.8C
        // یا هر فرمت دیگری که در ESP32 مشخص کرده‌اید.
        try {
            val parts = data.split("|")
            var t1Str = "--"
            var t2Str = "--"
            var t3Str = "--"

            for (part in parts) {
                if (part.startsWith("T1:")) {
                    t1Str = part.replace("T1:", "").replace("C", "").trim()
                } else if (part.startsWith("T2:")) {
                    t2Str = part.replace("T2:", "").replace("C", "").trim()
                } else if (part.startsWith("T3:")) {
                    t3Str = part.replace("T3:", "").replace("C", "").trim()
                }
            }

            uiHandler.post {
                updateSensorUI(tvTemp1, tvStatus1, t1Str)
                updateSensorUI(tvTemp2, tvStatus2, t2Str)
                updateSensorUI(tvTemp3, tvStatus3, t3Str)
            }
        } catch (e: Exception) {
            Log.e("DATA_PARSE", "Error parsing data: $data", e)
        }
    }

    private fun updateSensorUI(tvTemp: TextView, tvStatus: TextView, valueStr: String) {
        tvTemp.text = "$valueStr °C"

        val tempVal = valueStr.toFloatOrNull()
        if (tempVal != null) {
            if (tempVal >= alarmThreshold) {
                tvStatus.text = "ALARM"
                tvStatus.setTextColor(Color.RED)
            } else {
                tvStatus.text = "NORMAL"
                tvStatus.setTextColor(Color.GREEN)
            }
        } else {
            tvStatus.text = "ERROR"
            tvStatus.setTextColor(Color.GRAY)
        }
    }

    private fun updateConnectionStatus(text: String, color: Int) {
        tvConnStatus.text = text
        tvConnStatus.setTextColor(color)
    }

    private fun loadSettings() {
        val sharedPref = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        serverIp = sharedPref.getString("IP", "192.168.4.1") ?: "192.168.4.1"
        serverPort = sharedPref.getInt("PORT", 8888)
        alarmThreshold = sharedPref.getFloat("ALARM", 50.0f)
        smsEnabled = sharedPref.getBoolean("SMS_EN", false)
        smsNumber = sharedPref.getString("SMS_NUM", "") ?: ""
    }

    private fun saveSettings(ip: String, port: Int, alarm: Float, smsEn: Boolean, smsNum: String) {
        val sharedPref = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("IP", ip)
            putInt("PORT", port)
            putFloat("ALARM", alarm)
            putBoolean("SMS_EN", smsEn)
            putString("SMS_NUM", smsNum)
            apply()
        }
        
        // به روز رسانی متغیرهای حافظه
        serverIp = ip
        serverPort = port
        alarmThreshold = alarm
        smsEnabled = smsEn
        smsNumber = smsNum
        
        Toast.makeText(this, "Settings Saved. Reconnecting...", Toast.LENGTH_SHORT).show()

        // ری‌استارت کردن کلاینت برای اعمال IP/Port جدید
        stopTcpClient()
        startTcpClient()
    }

    private fun showSettingsDialog() {
        // ایجاد AlertDialog با تم پیش‌فرض اندروید (رفع خطای Unresolved reference)
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Settings")

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        builder.setView(view)

        val etIp = view.findViewById<EditText>(R.id.etIp)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etAlarm = view.findViewById<EditText>(R.id.etAlarm)
        val swSms = view.findViewById<Switch>(R.id.swSms)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)

        // پر کردن مقادیر فعلی
        etIp.setText(serverIp)
        etPort.setText(serverPort.toString())
        etAlarm.setText(alarmThreshold.toString())
        swSms.isChecked = smsEnabled
        etPhone.setText(smsNumber)

        builder.setPositiveButton("Save") { dialog, _ ->
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: 8888
            val alarm = etAlarm.text.toString().toFloatOrNull() ?: 50.0f
            val smsEn = swSms.isChecked
            val phone = etPhone.text.toString().trim()

            saveSettings(ip, port, alarm, smsEn, phone)
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }
}
