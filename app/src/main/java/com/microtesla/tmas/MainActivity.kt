package com.microtesla.tmas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        var lastTemp1: Float = 0f
        var lastTemp2: Float = 0f
        var lastTemp3: Float = 0f
    }

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

    private var mqttClient: MqttClient? = null
    private val broker = "tcp://broker.hivemq.com:1883"
    private val topic = "microtesla/tmas/data"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastSmsTimeS1 = 0L
    private val SMS_COOLDOWN = 60000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request SMS Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                ),
                101
            )
        }

        // Bind Views
        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvSensor1 = findViewById(R.id.tvSensor1)
        tvSensor2 = findViewById(R.id.tvSensor2)
        tvSensor3 = findViewById(R.id.tvSensor3)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        setupChart(chart1, "#00D2D3")
        setupChart(chart2, "#10AC84")
        setupChart(chart3, "#FF6B6B")

        connectToMQTT()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Settings")
            ?.setIcon(android.R.drawable.ic_menu_preferences)
            ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            showSettingsDialog()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val etM1 = EditText(this).apply {
            hint = "Manager 1 (Primary)"
            setText(prefs.getString("manager1", ""))
        }
        val etM2 = EditText(this).apply {
            hint = "Manager 2"
            setText(prefs.getString("manager2", ""))
        }
        val cbM2 = CheckBox(this).apply {
            text = "Active Manager 2"
            isChecked = prefs.getBoolean("manager2_active", false)
        }
        val etM3 = EditText(this).apply {
            hint = "Manager 3"
            setText(prefs.getString("manager3", ""))
        }
        val cbM3 = CheckBox(this).apply {
            text = "Active Manager 3"
            isChecked = prefs.getBoolean("manager3_active", false)
        }

        val etMin1 = EditText(this).apply {
            hint = "Sensor 1 Min Temp"
            setText(prefs.getFloat("s1_min", 0f).toString())
        }
        val etMax1 = EditText(this).apply {
            hint = "Sensor 1 Max Temp"
            setText(prefs.getFloat("s1_max", 100f).toString())
        }

        layout.addView(etM1)
        layout.addView(cbM2)
        layout.addView(etM2)
        layout.addView(cbM3)
        layout.addView(etM3)
        layout.addView(TextView(this).apply {
            text = "Thresholds:"
            setPadding(0, 20, 0, 0)
        })
        layout.addView(etMin1)
        layout.addView(etMax1)

        val scrollView = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("System Settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val minVal = etMin1.text.toString().toFloatOrNull() ?: 0f
                val maxVal = etMax1.text.toString().toFloatOrNull() ?: 100f
                prefs.edit().apply {
                    putString("manager1", etM1.text.toString())
                    putString("manager2", etM2.text.toString())
                    putBoolean("manager2_active", cbM2.isChecked)
                    putString("manager3", etM3.text.toString())
                    putBoolean("manager3_active", cbM3.isChecked)
                    putFloat("s1_min", minVal)
                    putFloat("s1_max", maxVal)
                    apply()
                }
                Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupChart(chart: LineChart, colorHex: String) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = Color.parseColor("#8A94A6")

        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.parseColor("#8A94A6")
        leftAxis.gridColor = Color.parseColor("#252932")

        chart.axisRight.isEnabled = false
        
        chart.data = LineData(LineDataSet(mutableListOf(), "").apply {
            color = Color.parseColor(colorHex)
            lineWidth = 2.5f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        })
    }

    private fun addEntryToChart(chart: LineChart, value: Float, currentIndex: Float): Float {
        val data = chart.data ?: return currentIndex
        val set = data.getDataSetByIndex(0) ?: return currentIndex
        set.addEntry(Entry(currentIndex, value))

        if (set.entryCount > maxEntries) {
            set.removeEntry(0)
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxEntries.toFloat())
        chart.moveViewToX(currentIndex)

        return currentIndex + 1f
    }

    private fun connectToMQTT() {
        val clientId = "TMAS_Android_" + UUID.randomUUID().toString()
        try {
            mqttClient = MqttClient(broker, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    mainHandler.post {
                        tvConnStatus.text = "Disconnected"
                        tvConnStatus.setTextColor(Color.parseColor("#FF6B6B"))
                    }
                    Thread {
                        Thread.sleep(3000)
                        connectToMQTT()
                    }.start()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let {
                        val msgStr = String(it.payload)
                        mainHandler.post { handleIncomingData(msgStr) }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // نیازی به پیاده‌سازی برای سابسکرایبر نیست
                }
            })

            Thread {
                try {
                    mqttClient?.connect(options)
                    mqttClient?.subscribe(topic)
                    mainHandler.post {
                        tvConnStatus.text = "● Live Online"
                        tvConnStatus.setTextColor(Color.parseColor("#10AC84"))
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        tvConnStatus.text = "Retrying..."
                        tvConnStatus.setTextColor(Color.parseColor("#FDCB6E"))
                    }
                    Thread.sleep(4000)
                    connectToMQTT()
                }
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAndSendAlarm(sensorName: String, temp: Float, min: Float, max: Float, lastSmsTime: Long): Long {
        if (min != 0f || max != 100f) {
            if (temp < min || temp > max) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSmsTime > SMS_COOLDOWN) {
                    val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
                    val m1 = prefs.getString("manager1", "") ?: ""
                    val m2 = prefs.getString("manager2", "") ?: ""
                    val m3 = prefs.getString("manager3", "") ?: ""
                    val m2Active = prefs.getBoolean("manager2_active", false)
                    val m3Active = prefs.getBoolean("manager3_active", false)

                    val msg = "Alert $sensorName: $temp C (Allowed: $min-$max)"
                    val smsManager = SmsManager.getDefault()

                    try {
                        if (m1.isNotEmpty()) smsManager.sendTextMessage(m1, null, msg, null, null)
                        if (m2Active && m2.isNotEmpty()) smsManager.sendTextMessage(m2, null, msg, null, null)
                        if (m3Active && m3.isNotEmpty()) smsManager.sendTextMessage(m3, null, msg, null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return currentTime
                }
            }
        }
        return lastSmsTime
    }

    private fun handleIncomingData(message: String) {
        try {
            val json = JSONObject(message)
            val s1 = json.optDouble("s1", Double.NaN).toFloat()
            val s2 = json.optDouble("s2", Double.NaN).toFloat()
            val s3 = json.optDouble("s3", Double.NaN).toFloat()

            val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)

            if (!s1.isNaN()) {
                lastTemp1 = s1
                tvSensor1.text = String.format("%.2f °C", s1)
                chartIndex1 = addEntryToChart(chart1, s1, chartIndex1)

                val min1 = prefs.getFloat("s1_min", 0f)
                val max1 = prefs.getFloat("s1_max", 100f)
                lastSmsTimeS1 = checkAndSendAlarm("Sensor 1", s1, min1, max1, lastSmsTimeS1)
            }

            if (!s2.isNaN()) {
                lastTemp2 = s2
                tvSensor2.text = String.format("%.2f °C", s2)
                chartIndex2 = addEntryToChart(chart2, s2, chartIndex2)
            }

            if (!s3.isNaN()) {
                lastTemp3 = s3
                tvSensor3.text = String.format("%.2f °C", s3)
                chartIndex3 = addEntryToChart(chart3, s3, chartIndex3)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
