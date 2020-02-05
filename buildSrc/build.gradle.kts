/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.kotlin.gradle.plugin.*

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

sourceSets["main"].withConvention(KotlinSourceSet::class) { kotlin.srcDirs("src") }