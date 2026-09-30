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

    // UI Views
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

    // MQTT Configuration
    private var mqttClient: MqttClient? = null
    private val broker = "tcp://broker.hivemq.com:1883"
    private val topic = "microtesla/tmas/data"
    private val mainHandler = Handler(Looper.getMainLooper())

    // SMS Configuration
    private var lastSmsTimeS1 = 0L
    private val SMS_COOLDOWN = 60000L // 1 minute delay between SMS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Request Permissions
        val permissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (!hasPermissions(this, *permissions)) {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }

        // 2. Bind Views (اتصال به XML)
        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvSensor1 = findViewById(R.id.tvSensor1)
        tvSensor2 = findViewById(R.id.tvSensor2)
        tvSensor3 = findViewById(R.id.tvSensor3)
        chart1 = findViewById(R.id.chart1)
        chart2 = findViewById(R.id.chart2)
        chart3 = findViewById(R.id.chart3)

        // 3. Setup Charts
        setupChart(chart1, "#00D2D3")
        setupChart(chart2, "#10AC84")
        setupChart(chart3, "#FF6B6B")

        // 4. Start MQTT Connection
        connectToMQTT()
    }

    private fun hasPermissions(context: Context, vararg permissions: String): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Settings")
            ?.setIcon(android.R.drawable.ic_menu_preferences)
            ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) showSettingsDialog()
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
        chart.setTouchEnabled(false)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = Color.parseColor("#8A94A6")

        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.parseColor("#8A94A6")
        leftAxis.gridColor = Color.parse)
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
        chart.setTouchEnabled(false)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = Color.parseColor("#8A94A6")

        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.parseColor("#8A94A6")
        leftAxis.gridColor = Color.parseColor("#252932")
        
        chart.axisRight.isEnabled = false

        val dataSet = LineDataSet(mutableListOf(), "").apply {
            color = Color.parseColor(colorHex).post {
                        try {
                            val json = JSONObject(payload)
                            if (json.has("s1")) {
                                val s1 = json.getDouble("s1").toFloat()
                                tvSensor1.text = "$s1 °C"
                                chartIndex1++
                                addEntryToChart(chart1, s1, chartIndex1)
                                checkThresholdsAndSMS(s1)
                            }
                            if (json.has("s2")) {
                                val s2 = json.getDouble("s2").toFloat()
                                tvSensor2.text = "$s2 °C"
                                chartIndex2++
                                addEntryToChart(chart2, s2, chartIndex2)
                            }
                            if (json.has("s3")) {
                                val s3 = json.getDouble("s3").toFloat()
                                tvSensor3.text = "$s3 °C"
                                chartIndex3++
                                addEntryToChart(chart3, s3, chartIndex3)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        try {
            mqttClient?.connect(options)
            mqttClient?.subscribe(this.topic)
            mainHandler.post {
                tvConnStatus.text = "Connected to Broker"
                tvConnStatus.setTextColor(Color.parseColor("#10AC84"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.post {
                tvConnStatus.text = "Connection Failed"
                tvConnStatus.setTextColor(Color.parseColor("#FF6B6B"))
            }
        }
    }

    private fun checkThresholdsAndSMS(temp: Float) {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        val minTemp = prefs.getFloat("s1_min", if (json.has("s2")) {
                                val s2 = json.getDouble("s2").toFloat()
                                tvSensor2.text = "$s2 °C"
                                chartIndex2++
                                addEntryToChart(chart2, s2, chartIndex2)
                            }
                            if (json.has("s3")) {
                                val s3 = json.getDouble("s3").toFloat()
                                tvSensor3.text = "$s3 °C"
                                chartIndex3++
                                addEntryToChart(chart3, s3, chartIndex3)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        try {
            mqttClient?.connect(options)
            mqttClient?.subscribe(this.topic)
            mainHandler.post {
                tvConnStatus.text = "Connected to Broker"
                tvConnStatus.setTextColor(Color.parseColor("#10AC84"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.post {
                tvConnStatus.text = "Connection Failed"
                tvConnStatus.setTextColor(Color.parseColor("#FF6B6B"))
            }
        }
    }

    private fun checkThresholdsAndSMS(temp: Float) {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        val minTemp = prefs.getFloat("s1_min", 0f)
        val maxTemp = prefs.getFloat("s1_max", 100f)
        
        if (temp < minTemp || temp > maxTemp) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSmsTimeS1 > SMS_COOLDOWN) {
                lastSmsTimeS1 = currentTime
                val msg = "TMAS ALARM: Sensor 1 گیت‌هاب اکشنز (GitHub Actions) بیلد خواهد شد.
