package com.microtesla.tmas

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)

        val etM1 = findViewById<EditText>(R.id.etManager1)
        val etM2 = findViewById<EditText>(R.id.etManager2)
        val cbM2 = findViewById<CheckBox>(R.id.cbManager2)
        val etM3 = findViewById<EditText>(R.id.etManager3)
        val cbM3 = findViewById<CheckBox>(R.id.cbManager3)

        val etS1Min = findViewById<EditText>(R.id.etS1Min)
        val etS1Max = findViewById<EditText>(R.id.etS1Max)
        val etS2Min = findViewById<EditText>(R.id.etS2Min)
        val etS2Max = findViewById<EditText>(R.id.etS2Max)
        val etS3Min = findViewById<EditText>(R.id.etS3Min)
        val etS3Max = findViewById<EditText>(R.id.etS3Max)

        // خواندن مقادیر ذخیره شده
        etM1.setText(prefs.getString("M1", ""))
        etM2.setText(prefs.getString("M2", ""))
        cbM2.isChecked = prefs.getBoolean("M2_EN", false)
        etM3.setText(prefs.getString("M3", ""))
        cbM3.isChecked = prefs.getBoolean("M3_EN", false)

        etS1Min.setText(prefs.getFloat("S1_MIN", 0f).toString())
        etS1Max.setText(prefs.getFloat("S1_MAX", 50f).toString())
        etS2Min.setText(prefs.getFloat("S2_MIN", 0f).toString())
        etS2Max.setText(prefs.getFloat("S2_MAX", 50f).toString())
        etS3Min.setText(prefs.getFloat("S3_MIN", 0f).toString())
        etS3Max.setText(prefs.getFloat("S3_MAX", 50f).toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.edit().apply {
                putString("M1", etM1.text.toString().trim())
                putString("M2", etM2.text.toString().trim())
                putBoolean("M2_EN", cbM2.isChecked)
                putString("M3", etM3.text.toString().trim())
                putBoolean("M3_EN", cbM3.isChecked)

                putFloat("S1_MIN", etS1Min.text.toString().toFloatOrNull() ?: 0f)
                putFloat("S1_MAX", etS1Max.text.toString().toFloatOrNull() ?: 50f)
                putFloat("S2_MIN", etS2Min.text.toString().toFloatOrNull() ?: 0f)
                putFloat("S2_MAX", etS2Max.text.toString().toFloatOrNull() ?: 50f)
                putFloat("S3_MIN", etS3Min.text.toString().toFloatOrNull() ?: 0f)
                putFloat("S3_MAX", etS3Max.text.toString().toFloatOrNull() ?: 50f)
                apply()
            }
            Toast.makeText(this, "تنظیمات با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
