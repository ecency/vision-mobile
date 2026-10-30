package com.ecency.core

import android.os.Build

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"
