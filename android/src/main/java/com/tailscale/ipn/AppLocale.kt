// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** Manual display language. Empty tag follows the system locale. */
object AppLocale {
  const val PREFS = "tailscale_locale"
  const val KEY = "app_locale"
  const val SYSTEM = ""
  const val ZH = "zh-CN"
  const val EN = "en"

  private var systemLocales: LocaleList? = null

  fun captureSystem(base: Context) {
    if (systemLocales == null) {
      systemLocales = base.resources.configuration.locales
    }
  }

  fun getTag(context: Context): String {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)
        ?: SYSTEM
  }

  fun setTag(context: Context, tag: String) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, tag)
        .commit()
    apply(context.applicationContext)
  }

  fun wrap(base: Context): Context {
    captureSystem(base)
    val tag = getTag(base)
    if (tag.isEmpty()) {
      return base
    }
    return withLocale(base, Locale.forLanguageTag(tag))
  }

  fun apply(context: Context) {
    val tag = getTag(context)
    val locale =
        if (tag.isEmpty()) {
          systemLocales?.get(0) ?: Locale.getDefault()
        } else {
          Locale.forLanguageTag(tag)
        }
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
  }

  private fun withLocale(base: Context, locale: Locale): Context {
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return base.createConfigurationContext(config)
  }
}
