package com.microtesla.tmas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as Array<*>?
                if (pdus != null) {
                    for (pdu in pdus) {
                        val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                        val sender = smsMessage.originatingAddress ?: ""
                        val messageBody = smsMessage.messageBody.trim().lowercase()

                        val prefs = context.getSharedPreferences("TMAS_PREFS", Context.MODE_PRIVATE)
                        val m1 = prefs.getString("manager1", "") ?: ""
                        val m2 = prefs.getString("manager2", "") ?: ""
                        val m3 = prefs.getString("manager3", "") ?: ""
                        val m2Active = prefs.getBoolean("manager2_active", false)
                        val m3Active = prefs.getBoolean("manager3_active", false)

                        // بررسی اینکه شماره ارسال کننده متعلق به یکی از مدیران فعال است یا نه
                        val isManager = (m1.isNotEmpty() && sender.contains(m1)) || 
                                        (m2Active && m2.isNotEmpty() && sender.contains(m2)) || 
                                        (m3Active && m3.isNotEmpty() && sender.contains(m3))

                        // بررسی کلمات کلیدی
                        if (isManager && (messageBody.contains("دما") || messageBody.contains("temp"))) {
                            val t1 = MainActivity.lastTemp1
                            val t2 = MainActivity.lastTemp2
                            val t3 = MainActivity.lastTemp3
                            val reply = "گزارش سیستم:\nدما سنسور ۱: $t1 °C\nدما سنسور ۲: $t2 °C\nدما سنسور ۳: $t3 °C"
                            
                            try {
                                val smsManager = SmsManager.getDefault()
                                smsManager.sendTextMessage(sender, null, reply, null, null)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }
}
