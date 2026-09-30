package com.microtesla.tmas

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var sensor1Val: TextView
    private lateinit var sensor2Val: TextView
    private lateinit var sensor3Val: TextView
    private lateinit var statusText: TextView

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private var mqttClient: MqttClient? = null
    private val broker = "tcp://broker.hivemq.com:1883"
    private val clientId = MqttClient.generateClientId()
    private val topic = "microtesla/tmas/data"

    private val maxEntries = 30
    private var chart1Index = 0f
    private var chart2Index = 0f
    private var chart3Index = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        sensor1Val = findViewById(R.id.sensor1Val)
        sensor2Val = findViewById(R.id.sensor2Val)
        sensor3Val = findViewById(R.id.sensor3Val)
        statusText = findViewById(R.id.statusText)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        // Setup Charts
        setupChart(chart1, "Sensor 1")
        setupChart(chart2, "Sensor 2")
        setupChart(chart3, "Sensor 3")

        // Connect to MQTT
        connectToMQTT()
    }

    private fun setupChart(chart: LineChart, label: String) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setDrawGridBackground(false)

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)

        val leftAxis = chart.axisLeft
        leftAxis.axisMinimum = 0f
        leftAxis.axisMaximum = 100f

        chart.axisRight.isEnabled = false

        val dataSet = LineDataSet(ArrayList(), label)
        dataSet.color = android.graphics.Color.BLUE
        dataSet.setCircleColor(android.graphics.Color.RED)
        dataSet.lineWidth = 2f
        dataSet.circleRadius = 4f
        dataSet.setDrawValues(false)

        val lineData = LineData(dataSet)
        chart.data = lineData
    }

    private fun addEntryToChart(chart: LineChart, value: Float, index: Float): Float {
        val data = chart.data ?: return index
        val set = data.getDataSetByIndex(0) ?: return index

        data.addEntry(Entry(index, value), 0)
        if (set.entryCount > maxEntries) {
            set.removeFirst()
            // Shift values left to create scrolling effect
            for (i in 0 until set.entryCount) {
                set.getEntryForIndex(i).x = i.toFloat()
            }
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxEntries.toFloat())
        
        // Move view to the end of the chart
        if(set.entryCount > 0){
             chart.moveViewToX(set.getEntryForIndex(set.entryCount-1).x)
        }
       
        chart.invalidate()
        return if (set.entryCount > maxEntries) maxEntries.toFloat() else index + 1f
    }

    private fun connectToMQTT() {
        try {
            mqttClient = MqttClient(broker, clientId, MemoryPersistence())
            
            val options = MqttConnectOptions()
            options.isCleanSession = true
            options.connectionTimeout = 10
            options.keepAliveInterval = 20

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    runOnUiThread {
                        statusText.text = "Disconnected"
                        statusText.setTextColor(android.graphics.Color.RED)
                    }
                    // Auto-reconnect can be implemented here
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload?.let { String(it) }
                    payload?.let { parseAndUpdateData(it) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // Not needed for subscriber
                }
            })

            Thread {
                try {
                    mqttClient?.connect(options)
                    mqttClient?.subscribe(topic, 0)
                    runOnUiThread {
                        statusText.text = "● Live Online"
                        statusText.setTextColor(android.graphics.Color.GREEN)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        statusText.text = "Connection Failed"
                        statusText.setTextColor(android.graphics.Color.RED)
                    }
                }
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseAndUpdateData(jsonString: String) {
        try {
            val jsonObject = JSONObject(jsonString)
            val s1 = jsonObject.optDouble("s1", 0.0).toFloat()
            val s2 = jsonObject.optDouble("s2", 0.0).toFloat()
            val s3 = jsonObject.optDouble("s3", 0.0).toFloat()

            Handler(Looper.getMainLooper()).post {
                sensor1Val.text = String.format("%.2f °C", s1)
                sensor2Val.text = String.format("%.2f °C", s2)
                sensor3Val.text = String.format("%.2f °C", s3)

                chart1Index = addEntryToChart(chart1, s1, chart1Index)
                chart2Index = addEntryToChart(chart2, s2, chart2Index)
                chart3Index = addEntryToChart(chart3, s3, chart3Index)
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
