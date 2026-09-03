@file:JvmName("MockitoKotlinHelpers")

package com.openlattice.chronicle.controllers

import org.mockito.ArgumentMatchers

/**
 * Re-exports of mockito matchers that return non-null values, so Kotlin's
 * non-null-parameter checks don't NPE at the .when(mock.foo(any())) line.
 *
 * mockito-core 5.x `Mockito.any()` / `Mockito.eq()` return null, which trips
 * Kotlin intrinsics on non-null parameters. mockito-kotlin's any/eq return
 * typed non-null sentinels via reified generics.
 */
inline fun <reified T : Any> kAny(): T = org.mockito.kotlin.any()
inline fun <reified T : Any> kEq(value: T): T = org.mockito.kotlin.eq(value)
fun kAnyString(): String = ArgumentMatchers.anyString()
fun kAnyInt(): Int = ArgumentMatchers.anyInt()
fun kAnyLong(): Long = ArgumentMatchers.anyLong()
fun <T> kAnyList(): List<T> = ArgumentMatchers.anyList()
fun <T> kAnySet(): Set<T> = ArgumentMatchers.anySet()
fun <K, V> kAnyMap(): Map<K, V> = ArgumentMatchers.anyMap()
