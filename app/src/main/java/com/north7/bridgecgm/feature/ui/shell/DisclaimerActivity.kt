package com.north7.bridgecgm.feature.ui.shell

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.north7.bridgecgm.databinding.ActivityDisclaimerBinding
import com.north7.bridgecgm.core.prefs.AppPrefs

/**
 * Mandatory first-launch disclaimer screen.
 *
 * The app only continues when the user explicitly accepts the disclaimer.
 * Rejecting (or pressing Back) exits the app immediately. The screen is
 * shown only once for the lifetime of the app installation.
 */
class DisclaimerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDisclaimerBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        if (prefs.disclaimerAccepted) {
            continueToNextScreen()
            return
        }

        binding = ActivityDisclaimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textStatement.text = DISCLAIMER_TEXT

        binding.btnAccept.setOnClickListener {
            prefs.disclaimerAccepted = true
            continueToNextScreen()
        }

        binding.btnReject.setOnClickListener {
            rejectAndExit()
        }

        onBackPressedDispatcher.addCallback(this) {
            rejectAndExit()
        }
    }

    private fun continueToNextScreen() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun rejectAndExit() {
        moveTaskToBack(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finishAffinity()
        }
    }

    private companion object {
        val DISCLAIMER_TEXT = """
            免責聲明 & 重要警告！
            請勿將本軟體或任何相關資料用於任何醫療目的或醫療決策。
            請勿依賴本系統以取得任何即時警報或時間緊迫的資料。
            請勿將本系統用於治療決策或取代專業醫療判斷。
            所有軟體和材料僅供參考，作為概念驗證，旨在為進一步研究提供可能性。
            我們不對任何用途的適用性做出任何聲明，所有內容均以「原樣」提供。系統的任何部分都可能隨時發生故障。
            如有任何醫療問題，請務必諮詢合格的醫療保健專業人員。
            使用任何設備時，請務必遵循血糖感測器或其他設備製造商的說明；除非醫生建議，否則請勿停止使用配套的讀取器或接收器。
            本軟體與任何設備製造商均無關聯，也未獲得任何設備製造商的認可，所有商標均為其各自所有者的財產。
            您使用本軟體的風險完全由您自行承擔。
            開發者並未就此軟體的使用收取任何費用。
            這是一個由志工創建的開源專案。原始碼免費開源，供您查閱和評估。
            使用此軟體和/或網站即表示您已年滿18歲，並已閱讀、瞭解並同意以上所有條款。

            Disclaimer & Important Warning!
            Do NOT use or rely on this software or any associated materials for any medical purpose or decision.
            Do NOT rely on this system for any real-time alarms or time critical data.
            Do NOT use or rely on this system for treatment decisions or use as a substitute for professional healthcare judgement.
            All software and materials have been provided for informational purposes only as a proof of concept to assist possibilities for further research.
            No claims at all are made about fitness for any purpose and everything is provided "AS IS". Any part of the system can fail at any time.
            Always seek the advice of a qualified healthcare professional for any medical questions.
            Always follow your glucose-sensor or other device manufacturers' instructions when using any equipment; do not discontinue use of accompanying reader or receiver, other than as advised by your doctor.
            This software is not associated with or endorsed by any equipment manufacturer and all trademarks are those of their respective owners.
            Your use of this software is entirely at your own risk.
            No charge has been made by the developers for the use of this software.
            This is an open-source project which has been created by volunteers. The source code is published free and open-source for you to inspect and evaluate.
            By using this software and/or website you agree that you are over 18 years of age and have read, understood and agree to all of the above.
        """.trimIndent()
    }
}
