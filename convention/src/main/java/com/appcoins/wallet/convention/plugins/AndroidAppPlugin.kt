package com.appcoins.wallet.convention.plugins

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.appcoins.wallet.convention.Config
import com.appcoins.wallet.convention.extensions.configureAndroidAndKotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

class AndroidAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply<AppPlugin>()
                apply("kotlin-android")
                apply("kotlin-android-extensions")
                apply("androidx.navigation.safeargs.kotlin")
                apply("dagger.hilt.android.plugin")
                apply("org.jetbrains.kotlin.kapt")
                apply("de.mannodermaus.android-junit5")
            }

            extensions.configure<BaseAppModuleExtension> {
                configureAndroidAndKotlin(this)
                defaultConfig.targetSdk = Config.android.targetSdk

                buildFeatures.buildConfig = true

                buildTypes {
                    getByName("debug") {
                        isMinifyEnabled = false
                        matchingFallbacks.add("release")
                    }

                    getByName("release") {
                        isMinifyEnabled = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                }
            }
        }

    }
}


