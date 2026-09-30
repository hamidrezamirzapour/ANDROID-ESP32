package com.microtesla.tmas

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
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

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTemp1: TextView
    private lateinit var tvTemp2: TextView
    private lateinit var tvTemp3: TextView
    private lateinit var btnSettings: ImageView

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private val maxEntries = 30
    private var chartIndex1 = 0f
    private var chartIndex2 = 0f
    private var chartIndex3 = 0f

    // Configurable MQTT Parameters
    private var brokerHost = "broker.hivemq.com"
    private var brokerPort = "1883"
    private var brokerTopic = "microtesla/tmas/data"

    private val clientId = "TMAS_Android_" + System.currentTimeMillis()
    private var mqttClient: MqttClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load saved preferences
        val prefs = getSharedPreferences("TMAS_PREFS", MODE_PRIVATE)
        brokerHost = prefs.getString("broker_host", "broker.hivemq.com") ?: "broker.hivemq.com"
        brokerPort = prefs.getString("broker_port", "1883") ?: "1883"
        brokerTopic = prefs.getString("broker_topic", "microtesla/tmas/data") ?: "microtesla/tmas/data"

        // Initialize Views
        tvStatus = findViewById(R.id.tvStatus)
        tvTemp1 = findViewById(R.id.tvTemp1)
        tvTemp2 = findViewById(R.id.tvTemp2)
        tvTemp3 = findViewById(R.id.tvTemp3)
        btnSettings = findViewById(R.id.btnSettings)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        btnSettings.setColorFilter(Color.WHITE)
        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Setup Charts
        setupChart(chart1, "Sensor 1", Color.CYAN)
        setupChart(chart2, "Sensor 2", Color.GREEN)
        setupChart(chart3, "Sensor 3", Color.parseColor("#FF9100"))

        connectMqtt()
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("تنظیمات ارتباط MQTT")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val tvHost = TextView(this).apply {
            text = "آدرس بروکر یا IP (مثلاً 192.168.1.50):"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 10, 0, 4)
        }
        val etHost = EditText(this).apply {
            hint = "e.g. 192.168.1.100 or broker.hivemq.com"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(brokerHost)
        }

        val tvPort = TextView(this).apply {
            text = "پورت (پیش‌فرض 1883):"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        val etPort = EditText(this).apply {
            hint = "1883"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(brokerPort)
        }

        val tvTopic = TextView(this).apply {
            text = "تاپیک سنسورها (Topic):"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        val etTopic = EditText(this).apply {
            hint = "e.g. microtesla/tmas/data or tmas/#"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(brokerTopic)
        }

        layout.addView(tvHost)
        layout.addView(etHost)
        layout.addView(tvPort)
        layout.addView(etPort)
        layout.addView(tvTopic)
        layout.addView(etTopic)

        builder.setView(layout)

        builder.setPositiveButton("ذخیره و اتصال") { dialog, _ ->
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().trim()
            val topic = etTopic.text.toString().trim()

            if (host.isNotEmpty() && port.isNotEmpty()) {
                brokerHost = host
                brokerPort = port
                brokerTopic = if (topic.isNotEmpty()) topic else "microtesla/tmas/data"

                val prefs = getSharedPreferences("TMAS_PREFS", MODE_PRIVATE)
                prefs.edit()
                    .putString("broker_host", brokerHost)
                    .putString("broker_port", brokerPort)
                    .putString("broker_topic", brokerTopic)
                    .apply()

                Toast.makeText(this, "تنظیمات ذخیره شد. در حال اتصال...", Toast.LENGTH_SHORT).show()
                connectMqtt()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("انصراف") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }

    private fun setupChart(chart: LineChart, label: String, color: Int) {
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setDrawGridBackground(false)

        chart.xAxis.textColor = Color.LTGRAY
        chart.axisLeft.textColor = Color.LTGRAY
        chart.axisRight.isEnabled = false
        chart.legend.textColor = Color.WHITE

        val desc = Description()
        desc.text = ""
        chart.description = desc

        val dataSet = LineDataSet(ArrayList<Entry>(), label)
        dataSet.color = color
        dataSet.setCircleColor(color)
        dataSet.lineWidth = 2f
        dataSet.circleRadius = 3f
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER

        val lineData = LineData(dataSet)
        chart.data = lineData
        chart.invalidate()
    }

    private fun addEntryToChart(chart: LineChart, value: Float, index: Float): Float {
        val data = chart.data
        if (data != null) {
            var set = data.getDataSetByIndex(0)
            if (set == null) {
                set = LineDataSet(ArrayList<Entry>(), "Data")
                data.addDataSet(set)
            }
            data.addEntry(Entry(index, value), 0)

            if (set.entryCount > maxEntries) {
                set.removeEntry(0)
            }

            data.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.setVisibleXRangeMaximum(maxEntries.toFloat())
            chart.moveViewToX(data.entryCount.toFloat())
        }
        return index + 1f
    }

    private fun connectMqtt() {
        runOnUiThread {
            tvStatus.text = "Status: Connecting..."
            tvStatus.setTextColor(Color.parseColor("#FFD600")) // Yellow
        }

        // Connection running in Background Thread to prevent NetworkOnMainThreadException
        Thread {
            try {
                try {
                    if (mqttClient?.isConnected == true) {
                        mqttClient?.disconnect()
                    }
                } catch (_: Exception) {}

                val brokerUri = "tcp://$brokerHost:$brokerPort"
                val persistence = MemoryPersistence()
                val client = MqttClient(brokerUri, clientId, persistence)
                mqttClient = client

                val options = MqttConnectOptions()
                options.isCleanSession = true
                options.connectionTimeout = 10
                options.keepAliveInterval = 20

                client.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        runOnUiThread {
                            tvStatus.text = "Status: Disconnected"
                            tvStatus.setTextColor(Color.RED)
                        }
                        Log.d("MQTT", "Connection Lost: ${cause?.message}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.toString()?.trim() ?: ""
                        runOnUiThread {
                            handleIncomingData(topic, payload)
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client.connect(options)

                // Subscribe to user configured topic
                client.subscribe(brokerTopic, 0)

                // Fallback wildcards to ensure incoming messages are never missed
                if (brokerTopic != "tmas/#") {
                    try { client.subscribe("tmas/#", 0) } catch (_: Exception) {}
                }
                if (brokerTopic != "microtesla/tmas/#") {
                    try { client.subscribe("microtesla/tmas/#", 0) } catch (_: Exception) {}
                }

                runOnUiThread {
                    tvStatus.text = "Status: Connected"
                    tvStatus.setTextColor(Color.GREEN)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatus.text = "Status: Error"
                    tvStatus.setTextColor(Color.RED)
                }
            }
        }.start()
    }

    private fun handleIncomingData(topic: String?, payload: String) {
        try {
            // Format 1: JSON payload (e.g. {"temp1": 25.4, "temp2": 30.1, "temp3": 28.5})
            if (payload.startsWith("{") && payload.endsWith("}")) {
                val json = JSONObject(payload)
                val v1 = when {
                    json.has("temp1") -> json.getDouble("temp1").toFloat()
                    json.has("s1") -> json.getDouble("s1").toFloat()
                    json.has("sensor1") -> json.getDouble("sensor1").toFloat()
                    else -> null
                }
                val v2 = when {
                    json.has("temp2") -> json.getDouble("temp2").toFloat()
                    json.has("s2") -> json.getDouble("s2").toFloat()
                    json.has("sensor2") -> json.getDouble("sensor2").toFloat()
                    else -> null
                }
                val v3 = when {
                    json.has("temp3") -> json.getDouble("temp3").toFloat()
                    json.has("s3") -> json.getDouble("s3").toFloat()
                    json.has("sensor3") -> json.getDouble("sensor3").toFloat()
                    else -> null
                }

                v1?.let {
                    tvTemp1.text = String.format("%.2f °C", it)
                    chartIndex1 = addEntryToChart(chart1, it, chartIndex1)
                }
                v2?.let {
                    tvTemp2.text = String.format("%.2f °C", it)
                    chartIndex2 = addEntryToChart(chart2, it, chartIndex2)
                }
                v3?.let {
                    tvTemp3.text = String.format("%.2f °C", it)
                    chartIndex3 = addEntryToChart(chart3, it, chartIndex3)
                }
                return
            }

            // Format 2: Comma separated (e.g. "25.4,30.1,28.5")
            if (payload.contains(",")) {
                val parts = payload.split(",")
                parts.getOrNull(0)?.trim()?.toFloatOrNull()?.let {
                    tvTemp1.text = String.format("%.2f °C", it)
                    chartIndex1 = addEntryToChart(chart1, it, chartIndex1)
                }
                parts.getOrNull(1)?.trim()?.toFloatOrNull()?.let {
                    tvTemp2.text = String.format("%.2f °C", it)
                    chartIndex2 = addEntryToChart(chart2, it, chartIndex2)
                }
                parts.getOrNull(2)?.trim()?.toFloatOrNull()?.let {
                    tvTemp3.text = String.format("%.2f °C", it)
                    chartIndex3 = addEntryToChart(chart3, it, chartIndex3)
                }
                return
            }

            // Format 3: Single float per sub-topic
            val singleVal = payload.toFloatOrNull()
            if (singleVal != null) {
                val t = topic?.lowercase() ?: ""
                when {
                    t.endsWith("sensor1") || t.endsWith("temp1") || t.endsWith("1") -> {
                        tvTemp1.text = String.format("%.2f °C", singleVal)
                        chartIndex1 = addEntryToChart(chart1, singleVal, chartIndex1)
                    }
                    t.endsWith("sensor2") || t.endsWith("temp2") || t.endsWith("2") -> {
                        tvTemp2.text = String.format("%.2f °C", singleVal)
                        chartIndex2 = addEntryToChart(chart2, singleVal, chartIndex2)
                    }
                    t.endsWith("sensor3") || t.endsWith("temp3") || t.endsWith("3") -> {
                        tvTemp3.text = String.format("%.2f °C", singleVal)
                        chartIndex3 = addEntryToChart(chart3, singleVal, chartIndex3)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
        } catch (_: Exception) {}
    }
}
