package com.north7.bridgecgm.feature.ui.shell

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.north7.bridgecgm.databinding.ActivityAboutBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set version text (hardcoded as requested)
        binding.appVersion.text = "Ver. 0.2"

        // Load license text from assets or raw resource
        binding.licenseText.text = loadLicenseText()
    }

    private fun loadLicenseText(): String {
        return try {
            val inputStream = assets.open("LICENSE")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val text = reader.readText()
            reader.close()
            text
        } catch (e: Exception) {
            // fallback: hardcoded license (if asset not found)
            "MIT License\n\nCopyright (c) 2026 [North7 Technology]\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the 'Software'), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."
        }
    }
}

