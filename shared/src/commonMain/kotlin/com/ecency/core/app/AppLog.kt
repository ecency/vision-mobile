package com.ecency.core.app

/**
 * A warning line in the platform log (Android logcat tag `Ecency`, iOS the unified log through NSLog). Never pass a
 * secret, a key, a token or account data beyond a username.
 */
internal expect fun appLogWarning(message: String)
