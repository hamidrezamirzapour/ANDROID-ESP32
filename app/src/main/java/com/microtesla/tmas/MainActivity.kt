package com.microtesla.tmas

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTemp1: TextView
    private lateinit var tvTemp2: TextView
    private lateinit var tvTemp3: TextView

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private val maxEntries = 30
    private var chartIndex1 = 0f
    private var chartIndex2 = 0f
    private var chartIndex3 = 0f

    private val brokerUri = "tcp://broker.hivemq.com:1883"
    private val clientId = "TMAS_Android_" + System.currentTimeMillis()
    private var mqttClient: MqttClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views based on activity_main.xml IDs
        tvStatus = findViewById(R.id.tvStatus)
        tvTemp1 = findViewById(R.id.tvTemp1)
        tvTemp2 = findViewById(R.id.tvTemp2)
        tvTemp3 = findViewById(R.id.tvTemp3)

        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        // Setup Charts
        setupChart(chart1, "Sensor 1", Color.CYAN)
        setupChart(chart2, "Sensor 2", Color.GREEN)
        setupChart(chart3, "Sensor 3", Color.parseColor("#FF9100")) // Orange

        connectMqtt()
    }

    private fun setupChart(chart: LineChart, label: String, color: Int) {
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setDrawGridBackground(false)

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
        try {
            val persistence = MemoryPersistence()
            mqttClient = MqttClient(brokerUri, clientId, persistence)

            val options = MqttConnectOptions()
            options.isCleanSession = true

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    runOnUiThread {
                        tvStatus.text = "Status: Disconnected"
                        tvStatus.setTextColor(Color.RED)
                    }
                    Log.d("MQTT", "Connection Lost")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: ""
                    runOnUiThread {
                        when (topic) {
                            "tmas/sensor1" -> {
                                val value = payload.toFloatOrNull() ?: 0f
                                tvTemp1.text = "$payload °C"
                                chartIndex1 = addEntryToChart(chart1, value, chartIndex1)
                            }
                            "tmas/sensor2" -> {
                                val value = payload.toFloatOrNull() ?: 0f
                                tvTemp2.text = "$payload °C"
                                chartIndex2 = addEntryToChart(chart2, value, chartIndex2)
                            }
                            "tmas/sensor3" -> {
                                val value = payload.toFloatOrNull() ?: 0f
                                tvTemp3.text = "$payload °C"
                                chartIndex3 = addEntryToChart(chart3, value, chartIndex3)
                            }
                        }
                    }
                }

                override fun deliveryComplete(token: GAPGPTMASKTOKENmgfc1vzkuwgX0X {}
            })

            mqttClient?.connect(options)
            tvStatus.text = "Status: Connected"
            tvStatus.setTextColor(Color.GREEN)

            // Subscribe to topics
            mqttClient?.subscribe("tmas/sensor1")
            mqttClient?.subscribe("tmas/sensor2")
            mqttClient?.subscribe("tmas/sensor3")

        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.text = "Status: Error"
            tvStatus.setTextColor(Color.RED)
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
