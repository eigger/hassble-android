package dev.eigger.hassble.ui

import android.content.Context
import com.charleskorn.kaml.YamlException
import dev.eigger.hassble.R
import dev.eigger.hassble.config.UnknownObdPresetException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

object ConfigErrorMapper {
    fun message(context: Context, exception: Throwable?): String {
        return when (exception) {
            is UnknownObdPresetException -> context.getString(R.string.config_err_unknown_preset, exception.preset)
            is UnknownHostException -> context.getString(R.string.config_err_unknown_host)
            is ConnectException -> context.getString(R.string.config_err_connect)
            is IOException -> context.getString(R.string.config_err_io)
            is YamlException -> context.getString(R.string.config_err_yaml, exception.localizedMessage ?: "")
            is SerializationException -> context.getString(R.string.config_err_parse, exception.localizedMessage ?: "")
            else -> context.getString(R.string.config_err_generic, exception?.localizedMessage ?: "")
        }
    }
}
