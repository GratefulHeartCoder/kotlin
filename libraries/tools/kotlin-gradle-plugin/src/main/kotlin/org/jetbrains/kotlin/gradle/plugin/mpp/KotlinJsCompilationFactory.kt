/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.Project

class KotlinJsCompilationFactory(
    val project: Project,
    val target: KotlinOnlyTarget<KotlinJsCompilation>
) : KotlinCompilationFactory<KotlinJsCompilation> {
    override val itemClass: Class<KotlinJsCompilation>
        get() = KotlinJsCompilation::class.java

    override fun create(name: String): KotlinJsCompilation =
        KotlinJsCompilation(target, name)
}