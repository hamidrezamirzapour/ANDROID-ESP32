package com.microtesla.tmas

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnStatus: TextView
    private lateinit var tvSensor1: TextView
    private lateinit var tvSensor2: TextView
    private lateinit var tvSensor3: TextView

    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var chart3: LineChart

    private var timeIndex = 0f

    @Volatile
    private var isRunning = false
    private var clientSocket: Socket? = null

    // ESP32 Access Point Default IP and Port
    private val espIp = "192.168.4.1"
    private val espPort = 8888

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

        setupChart(chart1, Color.CYAN)
        setupChart(chart2, Color.GREEN)
        setupChart(chart3, Color.YELLOW)

        startTcpClient()
    }

    private fun setupChart(chart: LineChart, color: Int) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setDrawGridBackground(false)
        chart.legend.isEnabled = false

        // تنظیم محور X (افقی)
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM // انتقال به پایین
        xAxis.setDrawGridLines(false)
        xAxis.setDrawLabels(false) // حذف اعداد نامفهوم برای پیوستگی نوار قلب
        xAxis.textColor = Color.LTGRAY

        // تنظیم محور Y (سمت چپ)
        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.LTGRAY
        leftAxis.setGridColor(Color.parseColor("#333333"))
        leftAxis.setDrawZeroLine(false)
        leftAxis.setStartAtZero(false)

        chart.axisRight.isEnabled = false // غیرفعال کردن محور سمت راست

        // ساخت مجموعه داده اولیه خالی
        val dataSet = LineDataSet(ArrayList(), "Temp").apply {
            this.color = color
            lineWidth = 2.5f
            setDrawCircles(false)       // عدم نمایش دایره برای هر نقطه
            setDrawValues(false)        // عدم چاپ عدد دما روی خط نمودار
            mode = LineDataSet.Mode.LINEAR // حالت خطی برای رفع مشکل باگ گرافیکی CUBIC
        }

        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun startTcpClient() {
        isRunning = true
        Thread {
            while (isRunning) {
                try {
                    runOnUiThread {
                        tvConnStatus.text = "Connecting to ESP32..."
                        tvConnStatus.setTextColor(Color.YELLOW)
                    }

                    val socket = Socket()
                    socket.connect(InetSocketAddress(espIp, espPort), 4000)
                    clientSocket = socket

                    runOnUiThread {
                        tvConnStatus.text = "CONNECTED (192.168.4.1:8888)"
                        tvConnStatus.setTextColor(Color.GREEN)
                    }

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    while (isRunning) {
                        val line = reader.readLine() ?: break
                        parseAndDisplay(line)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvConnStatus.text = "Disconnected. Retrying..."
                        tvConnStatus.setTextColor(Color.RED)
                    }
                } finally {
                    try { clientSocket?.close() } catch (_: Exception) {}
                }

                // Wait 2 seconds before reconnect attempt
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    // Parses string format: T1:25.40C|T2:26.10C|T3:24.80C
    private fun parseAndDisplay(rawLine: String) {
        try {
            val parts = rawLine.trim().split("|")
            var t1 = 0f
            var t2 = 0f
            var t3 = 0f

            for (part in parts) {
                val clean = part.replace("C", "").trim()
                val keyValue = clean.split(":")
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim().toFloatOrNull() ?: 0f
                    when (key) {
                        "T1", "S1" -> t1 = value
                        "T2", "S2" -> t2 = value
                        "T3", "S3" -> t3 = value
                    }
                }
            }

            runOnUiThread {
                tvSensor1.text = String.format("%.2f °C", t1)
                tvSensor2.text = String.format("%.2f °C", t2)
                tvSensor3.text = String.format("%.2f °C", t3)

                // اضافه کردن نقاط به نمودار
                addEntryToChart(chart1, t1, timeIndex)
                addEntryToChart(chart2, t2, timeIndex)
                addEntryToChart(chart3, t3, timeIndex)
                
                // افزایش اندیس زمان تا نمودار به سمت راست حرکت کند
                timeIndex += 1f
            }
        } catch (_: Exception) {
            // Ignore corrupted packets
        }
    }

    private fun addEntryToChart(chart: LineChart, value: Float, currentIndex: Float) {
        val data = chart.data ?: return
        var set = data.getDataSetByIndex(0)
        
        // اضافه کردن نقطه جدید به نمودار (X: زمان ، Y: دما)
        data.addEntry(Entry(currentIndex, value), 0)
        
        data.notifyDataChanged()
        chart.notifyDataSetChanged()

        // نمایش 40 نقطه اخیر و اسکرول به صورت پیوسته (شبیه نوار قلب)
        chart.setVisibleXRangeMaximum(40f)
        chart.moveViewToX(currentIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { clientSocket?.close() } catch (_: Exception) {}
    }
}
