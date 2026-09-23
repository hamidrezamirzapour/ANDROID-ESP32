package com.microtesla.tmas

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

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
    private val brokerUri = "tcp://broker.hivemq.com:1883"
    private val topic = "microtesla/tmas/data"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        connectToMQTT()
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

    private fun connectToMQTT() {
        val clientId = "TMAS_Android_" + UUID.randomUUID().toString().substring(0, 8)
        try {
            mqttClient = MqttClient(brokerUri, clientId, MemoryPersistence())
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
                        // Reconnect after 3s
                        mainHandler.postDelayed({ connectToMQTT() }, 3000)
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: return
                    mainHandler.post {
                        handleIncomingData(payload)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            Thread {
                try {
                    mqttClient?.connect(options)
                    mqttClient?.subscribe(topic, 0)
                    mainHandler.post {
                        tvConnStatus.text = "● Live Online"
                        tvConnStatus.setTextColor(Color.parseColor("#10AC84"))
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        tvConnStatus.text = "Retrying..."
                        tvConnStatus.setTextColor(Color.parseColor("#FF6B6B"))
                        mainHandler.postDelayed({ connectToMQTT() }, 4000)
                    }
                }
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleIncomingData(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            if (obj.has("s1")) {
                val s1 = obj.getDouble("s1").toFloat()
                tvSensor1.text = String.format("%.2f °C", s1)
                chartIndex1 = addEntryToChart(chart1, s1, chartIndex1)
            }
            if (obj.has("s2")) {
                val s2 = obj.getDouble("s2").toFloat()
                tvSensor2.text = String.format("%.2f °C", s2)
                chartIndex2 = addEntryToChart(chart2, s2, chartIndex2)
            }
            if (obj.has("s3")) {
                val s3 = obj.getDouble("s3").toFloat()
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
        } catch (e: Exception) {}
    }
}
