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
import com.microtesla.tmas.R
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

    private var lastSms.client.mqttv3.MqttClient
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
    private var lastSmsTimeS2 = 0L
    private var lastSmsTimeS3 = 0L
    private val SMS_COOLDOWN = 60000L // 60 seconds cooldown

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request SMS Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE showSettingsDialog() {
        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val etM1 = EditText(this).apply { hint = "شماره مدیر 1 (اصلی)"; setText(prefs.getString("manager1", "")) }
        val etM2 = EditText(this).apply { hint = "شماره مدیر 2"; setText(prefs.getString("manager2", "")) }
        val cbM2 = CheckBox(this).apply { text = "فعال بودن مدیر 2"; isChecked = prefs.getBoolean("manager2_active", false) }
        val etM3 = EditText(this).apply { hint = "شماره مدیر 3"; setText(prefs.getString("manager3", "")) }
        val cbM3 = CheckBox(this).apply { text = "فعال بودن مدیر 3"; isChecked = prefs.getBoolean("manager3_active", false) }

        val etMin1 = EditText(this).apply { hint = "حداقل دما سنسور 1"; setText(prefs.getFloat("s1_min", 0f).toString()) }
        val etMax1 = EditText(this).apply { hint = "حداکثر دما سنسور 1"; setText(prefs.getFloat("s1_max", 100f).toString()) }

        layout.addView(etM1)
        layout.addView(cbM2); layout.addView(etM2)
        layout.addView(cbM3); layout.addView(etM3)
        layout.addView(TextView(this).apply { text = "تنظیمات هشدار سنسور ۱:"; setPadding(0, 20, 0, 0) })
        layout.addView(etMin1); layout.addView(etMax1)

        ScrollView(this).apply { addView(layout) }.let {
            AlertDialog.Builder(this)
                .setTitle("تنظیمات سیستم")
                .setView(it)
                .setPositiveButton("ذخیره") { _, _ ->
                    prefs.edit().apply {
                        putString("manager1", etM1.text.toString())
                        putString("manager2", etM2.text.toString())
                        putBoolean("manager2_active", cbM2.isChecked)
                        putString("manager3", etM3.text.toString())
                        putBoolean("manager3_active", cbM3.isChecked)
                        putFloat("s1_min", etMin1.text.toString().toFloatOrNull()
